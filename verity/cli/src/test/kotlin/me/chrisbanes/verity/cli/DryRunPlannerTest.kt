package me.chrisbanes.verity.cli

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.doesNotContain
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import assertk.assertions.isSameInstanceAs
import assertk.assertions.messageContains
import com.github.ajalt.clikt.core.CliktError
import kotlin.test.Test
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import me.chrisbanes.verity.agent.NavigatorAgent
import me.chrisbanes.verity.agent.modelReply
import me.chrisbanes.verity.core.interaction.Interaction
import me.chrisbanes.verity.core.model.AssertMode
import me.chrisbanes.verity.core.model.Journey
import me.chrisbanes.verity.core.model.JourneyStep
import me.chrisbanes.verity.core.model.Platform
import me.chrisbanes.verity.device.MaestroFlowValidationInfrastructureException
import me.chrisbanes.verity.device.validateMaestroFlow

class DryRunPlannerTest {
  @Test
  fun `new interaction descriptions are pure and do not initialize the unmigrated navigator`() {
    val planner = DryRunPlanner(navigatorFactory = { error("must not initialize navigator") })
    val descriptions = listOf(
      Interaction.LaunchApp(),
      Interaction.LaunchApp(false),
      Interaction.InputText("  café  "),
      Interaction.DefaultScroll,
      Interaction.WaitForAnimation(),
      Interaction.WaitForAnimation(500),
      Interaction.WaitUntilVisible(text = "Next.*", timeoutMs = 1000),
      Interaction.WaitUntilVisible(resourceId = "app:id/next", timeoutMs = 2000),
    ).map(planner::describeInteraction)
    assertThat(descriptions).isEqualTo(
      listOf(
        "LaunchApp(clearState=null)",
        "LaunchApp(clearState=false)",
        "InputText(  café  )",
        "DefaultScroll",
        "WaitForAnimation(timeoutMs=null)",
        "WaitForAnimation(timeoutMs=500)",
        "WaitUntilVisible(text=Next.*, resourceId=null, timeoutMs=1000)",
        "WaitUntilVisible(text=null, resourceId=app:id/next, timeoutMs=2000)",
      ),
    )
  }

  @Test fun `actual slow navigator model failure has contextual preview exit5`() = runTest {
    val navigator = NavigatorAgent("context") { _, _ -> modelReply("appId: com.example\n---\n- tapOn:") }
    val planner = DryRunPlanner(navigatorFactory = { DryRunNavigator(navigator::generate) })
    val journey = Journey("Preview", "com.example", Platform.IOS, listOf(JourneyStep.Action("complete onboarding wizard")))
    val failure = runCatching { planner.plan(resolvedJourney(journey)) }.exceptionOrNull() as CliktError
    assertThat(failure.statusCode).isEqualTo(5)
    assertThat(failure.message.orEmpty()).contains("Preview.journey.yaml")
    assertThat(failure.message.orEmpty()).contains("segment 0")
    assertThat(failure.message.orEmpty()).contains("Navigator flow response was invalid")
  }

  @Test
  fun `fast path actions are represented without invoking navigator`() = runTest {
    var navigatorCalls = 0
    val planner = DryRunPlanner(
      navigatorFactory = {
        navigatorCalls += 1
        DryRunNavigator { _, _, _, _ -> error("navigator should not be called") }
      },
    )
    val journey = Journey(
      name = "Fast journey",
      app = "com.example.app",
      platform = Platform.ANDROID_TV,
      steps = listOf(
        JourneyStep.Action("press d-pad down"),
        JourneyStep.Action("press select"),
        JourneyStep.Assert("Home", AssertMode.VISIBLE),
      ),
    )

    val report = planner.plan(resolvedJourney(journey))

    assertThat(navigatorCalls).isEqualTo(0)
    assertThat(report.launchYaml).isEqualTo("appId: com.example.app\n---\n- launchApp")
    assertThat(report.segments).transform { it.size }.isEqualTo(1)
    val segment = report.segments.single()
    assertThat(segment.index).isEqualTo(0)
    assertThat(segment.actions?.kind).isEqualTo(DryRunExecutionKind.FAST_PATH)
    assertThat(segment.actions?.instructions.orEmpty()).containsExactly("press d-pad down", "press select")
    assertThat(segment.actions?.interactions.orEmpty()).contains("KeyPress(DPAD_DOWN)")
    assertThat(segment.actions?.yaml).isNull()
    assertThat(segment.assertion?.description).isEqualTo("Home")
    assertThat(segment.assertion?.mode).isEqualTo(AssertMode.VISIBLE)
  }

  @Test
  fun `slow path actions include generated yaml`() = runTest {
    val planner = DryRunPlanner(
      navigatorFactory = {
        DryRunNavigator { actions, appId, platform, context ->
          assertThat(actions).containsExactly("complete onboarding wizard")
          assertThat(appId).isEqualTo("com.example.app")
          assertThat(platform).isEqualTo(Platform.ANDROID_MOBILE)
          assertThat(context).isEqualTo("project context")
          "appId: com.example.app\n---\n- tapOn: \"Settings\""
        }
      },
      context = "project context",
    )
    val journey = Journey(
      name = "Slow journey",
      app = "com.example.app",
      platform = Platform.ANDROID_MOBILE,
      steps = listOf(JourneyStep.Action("complete onboarding wizard")),
    )

    val segment = planner.plan(resolvedJourney(journey)).segments.single()

    assertThat(segment.actions?.kind).isEqualTo(DryRunExecutionKind.SLOW_PATH)
    assertThat(segment.actions?.yaml).isEqualTo("appId: com.example.app\n---\n- tapOn: \"Settings\"")
  }

  @Test
  fun `slow path action generation failure includes journey file and segment`() = runTest {
    val planner = DryRunPlanner(
      navigatorFactory = {
        DryRunNavigator { _, _, _, _ -> error("navigator exploded") }
      },
    )
    val journey = Journey(
      name = "Slow journey",
      app = "com.example.app",
      platform = Platform.ANDROID_MOBILE,
      steps = listOf(JourneyStep.Action("complete onboarding wizard")),
    )

    val failure = assertFailure {
      planner.plan(resolvedJourney(journey))
    }
    failure.messageContains("Slow journey.journey.yaml")
    failure.messageContains("segment 0")
    failure.messageContains("Preview generation setup failed")
  }

  @Test
  fun `loop reports fast path mapping when action maps`() = runTest {
    val planner = DryRunPlanner(
      navigatorFactory = { DryRunNavigator { _, _, _, _ -> error("navigator should not be called") } },
    )
    val journey = Journey(
      name = "Loop journey",
      app = "com.example.app",
      platform = Platform.ANDROID_TV,
      steps = listOf(JourneyStep.Loop(action = "press d-pad down", until = "Settings", max = 3)),
    )

    val loop = planner.plan(resolvedJourney(journey)).segments.single().loop

    assertThat(loop?.kind).isEqualTo(DryRunExecutionKind.FAST_PATH)
    assertThat(loop?.action).isEqualTo("press d-pad down")
    assertThat(loop?.until).isEqualTo("Settings")
    assertThat(loop?.max).isEqualTo(3)
    assertThat(loop?.interaction).isEqualTo("KeyPress(DPAD_DOWN)")
    assertThat(loop?.yaml).isNull()
  }

  @Test
  fun `loop reports generated yaml when action does not map`() = runTest {
    val planner = DryRunPlanner(
      navigatorFactory = {
        DryRunNavigator { actions, appId, platform, context ->
          assertThat(actions).containsExactly("navigate to settings page")
          assertThat(appId).isEqualTo("com.example.app")
          assertThat(platform).isEqualTo(Platform.ANDROID_MOBILE)
          assertThat(context).isEqualTo("loop context")
          "appId: com.example.app\n---\n- tapOn: \"Settings\""
        }
      },
      context = "loop context",
    )
    val journey = Journey(
      name = "Slow loop journey",
      app = "com.example.app",
      platform = Platform.ANDROID_MOBILE,
      steps = listOf(JourneyStep.Loop(action = "navigate to settings page", until = "Settings", max = 2)),
    )

    val loop = planner.plan(resolvedJourney(journey)).segments.single().loop

    assertThat(loop?.kind).isEqualTo(DryRunExecutionKind.SLOW_PATH)
    assertThat(loop?.yaml).isEqualTo("appId: com.example.app\n---\n- tapOn: \"Settings\"")
  }

  @Test
  fun `slow path loop generation failure includes journey file and segment`() = runTest {
    val planner = DryRunPlanner(
      navigatorFactory = {
        DryRunNavigator { _, _, _, _ -> error("loop navigator exploded") }
      },
    )
    val journey = Journey(
      name = "Slow loop journey",
      app = "com.example.app",
      platform = Platform.ANDROID_MOBILE,
      steps = listOf(JourneyStep.Loop(action = "navigate to settings page", until = "Settings", max = 2)),
    )

    val failure = assertFailure {
      planner.plan(resolvedJourney(journey))
    }
    failure.messageContains("Slow loop journey.journey.yaml")
    failure.messageContains("segment 0")
    failure.messageContains("Preview generation setup failed")
  }

  @Test
  fun `renderer includes launch yaml segments fast path slow path loops and assertions`() = runTest {
    val report = DryRunJourneyReport(
      resolvedJourney = resolvedJourney(
        Journey(
          name = "Rendered journey",
          app = "com.example.app",
          platform = Platform.ANDROID_MOBILE,
          steps = emptyList(),
        ),
      ),
      launchYaml = "appId: com.example.app\n---\n- launchApp",
      segments = listOf(
        DryRunSegmentReport(
          index = 0,
          actions = DryRunActionGroupReport(
            instructions = listOf("tap Settings"),
            kind = DryRunExecutionKind.FAST_PATH,
            interactions = listOf("TapOnText(Settings)"),
          ),
          assertion = DryRunAssertionReport("Settings", AssertMode.VISIBLE),
        ),
        DryRunSegmentReport(
          index = 1,
          actions = DryRunActionGroupReport(
            instructions = listOf("open account details"),
            kind = DryRunExecutionKind.SLOW_PATH,
            yaml = "appId: com.example.app\n---\n- tapOn: \"Account\"",
          ),
          loop = DryRunLoopReport(
            action = "navigate to logout",
            until = "Logout",
            max = 2,
            kind = DryRunExecutionKind.SLOW_PATH,
            yaml = "appId: com.example.app\n---\n- scroll",
          ),
        ),
      ),
    )

    val markdown = DryRunRenderer.renderJourney(report)

    assertThat(markdown).isEqualTo(
      """
      # Dry Run: Rendered journey

      File: Rendered journey.journey.yaml
      App: com.example.app
      Platform: ANDROID_MOBILE

      ## Launch Flow
      ```yaml
      appId: com.example.app
      ---
      - launchApp
      ```

      ## Segment 0

      Actions:
      - tap Settings
      Kind: FAST_PATH
      Interactions:
      - TapOnText(Settings)

      Assertion: [VISIBLE] Settings

      ## Segment 1

      Actions:
      - open account details
      Kind: SLOW_PATH
      Generated YAML:
      ```yaml
      appId: com.example.app
      ---
      - tapOn: "Account"
      ```

      Loop: navigate to logout until Logout, max 2
      Kind: SLOW_PATH
      Generated Loop YAML:
      ```yaml
      appId: com.example.app
      ---
      - scroll
      ```
      """.trimIndent(),
    )
  }

  @Test
  fun `renderer uses longer yaml fence when yaml contains backticks`() = runTest {
    val report = DryRunJourneyReport(
      resolvedJourney = resolvedJourney(
        Journey(
          name = "Backtick journey",
          app = "com.example.app",
          platform = Platform.ANDROID_MOBILE,
          steps = emptyList(),
        ),
      ),
      launchYaml = "appId: com.example.app\n---\n- inputText: ```code```",
      segments = emptyList(),
    )

    val markdown = DryRunRenderer.renderJourney(report)

    assertThat(markdown).isEqualTo(
      """
      # Dry Run: Backtick journey

      File: Backtick journey.journey.yaml
      App: com.example.app
      Platform: ANDROID_MOBILE

      ## Launch Flow
      ````yaml
      appId: com.example.app
      ---
      - inputText: ```code```
      ````
      """.trimIndent(),
    )
  }

  @Test
  fun `complete mapped loop body previews every interaction in order without a navigator`() = runTest {
    val planner = DryRunPlanner(navigatorFactory = { error("must not create navigator") })
    val journey = Journey(
      "Body preview",
      "example.app",
      Platform.ANDROID_TV,
      listOf(JourneyStep.Loop("Press D-pad down; press D-pad right", "visually Settings", 3)),
    )
    val report = planner.plan(resolvedJourney(journey))
    assertThat(report.segments.single().loop?.interactions).isEqualTo(listOf("KeyPress(DPAD_DOWN)", "KeyPress(DPAD_RIGHT)"))
    val rendered = DryRunRenderer.renderJourney(report)
    assertThat(rendered).contains("Loop: Press D-pad down; press D-pad right until visually Settings, max 3")
    assertThat(rendered).contains("Interactions:\n- KeyPress(DPAD_DOWN)\n- KeyPress(DPAD_RIGHT)")
  }

  @Test
  fun `mixed loop body generates one complete body preserving the mapped prefix`() = runTest {
    val generated = mutableListOf<List<String>>()
    val yaml = "appId: example.app\n---\n- pressKey: Remote Dpad Down\n- tapOn: Settings"
    val planner = DryRunPlanner(navigatorFactory = {
      DryRunNavigator { actions, _, _, _ ->
        generated += actions
        yaml
      }
    })
    val journey = Journey(
      "Mixed preview",
      "example.app",
      Platform.ANDROID_TV,
      listOf(JourneyStep.Loop("Press D-pad down; navigate to settings page", "Settings has focus", 3)),
    )
    val report = planner.plan(resolvedJourney(journey))
    assertThat(generated).isEqualTo(listOf(listOf("Press D-pad down", "navigate to settings page")))
    assertThat(report.segments.single().loop?.kind).isEqualTo(DryRunExecutionKind.SLOW_PATH)
    assertThat(DryRunRenderer.renderJourney(report)).contains(yaml)
  }

  @Test fun `action and complete loop previews classify every actual navigator failure safely`() = runTest {
    for (loop in listOf(false, true)) {
      for (type in listOf("request", "timeout", "truncated", "empty", "invalid", "create", "write", "read", "cleanup", "resource", "sdk")) {
        val navigator = NavigatorAgent("context", validateFlow = { yaml ->
          when (type) {
            "create" -> validateMaestroFlow(yaml, createTempFile = { throw java.nio.file.AccessDeniedException(previewSentinel) })

            "write" -> validateMaestroFlow(yaml, writeFlow = { _, _ -> throw java.io.IOException(previewSentinel) })

            "read" -> validateMaestroFlow(yaml, readFlow = { throw java.io.IOException(previewSentinel) })

            "cleanup" -> validateMaestroFlow(yaml, deleteFlow = {
              java.nio.file.Files.delete(it)
              throw java.io.IOException(previewSentinel)
            })

            "resource" -> validateMaestroFlow("appId: com.example\n---\n- runFlow: /unavailable/missing-verity.yaml")

            "sdk" -> validateMaestroFlow(yaml, beforeResponseCheck = { throw IllegalStateException(previewSentinel) })

            else -> validateMaestroFlow(yaml)
          }
        }) { _, _ ->
          when (type) {
            "request" -> error(previewSentinel)

            "timeout" -> {
              delay(30_001)
              modelReply("unused")
            }

            "truncated" -> modelReply("appId: com.example\n---\n- launchApp", "length")

            "empty" -> modelReply(" ")

            "invalid" -> modelReply("appId: com.example\n---\n- tapOn: \"$previewSentinel")

            else -> modelReply("appId: com.example\n---\n- launchApp")
          }
        }
        val planner = DryRunPlanner(navigatorFactory = { DryRunNavigator(navigator::generate) })
        val journey = Journey("Preview", "com.example", Platform.IOS, listOf(if (loop) JourneyStep.Loop("Press back; complete onboarding wizard", "ready", 2) else JourneyStep.Action("complete onboarding wizard")))
        val failure = runCatching { planner.plan(resolvedJourney(journey)) }.exceptionOrNull() as CliktError
        assertThat(failure.statusCode).isEqualTo(if (type in listOf("request", "timeout", "truncated", "empty", "invalid")) 5 else 3)
        assertThat(failure.message.orEmpty()).contains("Preview.journey.yaml")
        assertThat(failure.message.orEmpty()).contains("segment 0")
        assertThat(failure.message.orEmpty()).doesNotContain(previewSentinel)
        assertThat(failure.cause).isNull()
      }
    }
  }

  @Test fun `preview preserves caller cancellation and shorter enclosing timeout`() = runTest {
    val caller = object : kotlin.coroutines.cancellation.CancellationException("caller") {
      val marker = Any()
    }
    for (loop in listOf(false, true)) {
      val journey = Journey("Preview", "com.example", Platform.IOS, listOf(if (loop) JourneyStep.Loop("complete onboarding wizard", "ready", 2) else JourneyStep.Action("complete onboarding wizard")))
      val navigator = NavigatorAgent("context") { _, _ -> throw caller }
      val planner = DryRunPlanner(navigatorFactory = { DryRunNavigator(navigator::generate) })
      assertThat(runCatching { planner.plan(resolvedJourney(journey)) }.exceptionOrNull()).isSameInstanceAs(caller)
      val slow = NavigatorAgent("context") { _, _ ->
        delay(30_001)
        modelReply("unused")
      }
      val timeout = runCatching { withTimeout(100) { DryRunPlanner(navigatorFactory = { DryRunNavigator(slow::generate) }).plan(resolvedJourney(journey)) } }.exceptionOrNull()
      assertThat(timeout is TimeoutCancellationException).isEqualTo(true)
    }
  }

  @Test fun `navigator creation and unknown host generation failure remain safe preview setup3`() = runTest {
    for (creation in listOf(false, true)) {
      val planner = DryRunPlanner(navigatorFactory = {
        if (creation) error(previewSentinel)
        DryRunNavigator { _, _, _, _ -> error(previewSentinel) }
      })
      val failure = runCatching { planner.plan(resolvedJourney(Journey("Preview", "com.example", Platform.IOS, listOf(JourneyStep.Action("complete onboarding wizard"))))) }.exceptionOrNull() as CliktError
      assertThat(failure.statusCode).isEqualTo(3)
      assertThat(failure.message.orEmpty()).doesNotContain(previewSentinel)
    }
  }

  private val previewSentinel = "sk-private Bearer private-credential eyJprivate.payload.signature HTTP_BODY_PRIVATE HEADER_PRIVATE"

  private fun resolvedJourney(journey: Journey): ResolvedJourney = ResolvedJourney(
    file = java.io.File("${journey.name}.journey.yaml"),
    journey = journey,
  )
}
