package me.chrisbanes.verity.cli

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import assertk.assertions.messageContains
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import me.chrisbanes.verity.core.model.AssertMode
import me.chrisbanes.verity.core.model.Journey
import me.chrisbanes.verity.core.model.JourneyStep
import me.chrisbanes.verity.core.model.Platform

class DryRunPlannerTest {
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
    failure.messageContains("navigator exploded")
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
    failure.messageContains("loop navigator exploded")
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

  private fun resolvedJourney(journey: Journey): ResolvedJourney = ResolvedJourney(
    file = java.io.File("${journey.name}.journey.yaml"),
    journey = journey,
  )
}
