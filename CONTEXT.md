# Verity

Verity describes and checks user journeys through applications on Android TV, Android mobile, and iOS. This glossary defines the language used to author journeys, describe their execution, and interpret their results.

## Language

### Journeys

**Journey**:
A named sequence of steps through a particular application on a target platform.
_Avoid_: Flow, suite, run

**Step**:
An action, assertion, loop, or wait within a journey.

**Action**:
An instruction to interact with the application.

**Assertion**:
An expectation about the application's current screen state.
_Avoid_: Action, loop condition

**Assertion mode**:
The basis for judging an assertion: visible text, focused content, accessibility tree, or screenshot.
_Avoid_: Assertion strategy

**Assertion strategy**:
The policy for choosing assertion modes when a journey does not specify them explicitly.
_Avoid_: Assertion mode

**Loop**:
A repeated action with a stopping condition and a maximum number of repetitions.

**Wait**:
An action-free check of current state that repeats serially until satisfied or its elapsed time limit expires.
_Avoid_: Loop, fixed delay

**Segment**:
A journey checkpoint consisting of a group of actions with an optional following assertion, or a standalone loop or wait. Actions remaining at the end of a journey also form a segment.
_Avoid_: Step, journey

### Execution

**Suite**:
The ordered collection of journeys selected for execution together. A suite can contain a single journey.
_Avoid_: Journey, run

**Run**:
One execution of a suite, with its own outcomes and artifacts.
_Avoid_: Suite, journey

**Dry run**:
A preview of how a suite would be executed, without interacting with a device or evaluating assertions.
_Avoid_: Run, successful test

**Flow**:
A sequence of device automation commands for launching an application or carrying out journey actions.
_Avoid_: Journey, suite

**Journey memory**:
Bounded record of earlier verdicts, the execution trail and earlier screenshots from the current journey, given to inspections as reference rather than proof. It never carries over to another journey.
_Avoid_: History, project context

**Execution trail**:
The ordered record of what a journey executed, one entry per interaction or flow, with the focus observed before and after each.
_Avoid_: Generated flow, evidence

**Project context**:
Application-specific guidance supplied to help interpret journey instructions.
_Avoid_: Domain glossary, project configuration

**Bundled context**:
Verity's built-in guidance for device automation and platform controls.
_Avoid_: Project context

### Outcomes

**Run artifact**:
A saved record of a run, such as a result, generated flow, or assertion evidence.

**Evidence**:
A captured observation of screen state used to judge an assertion, such as an accessibility tree or screenshot.
_Avoid_: Generated flow, result

**Journey result**:
The outcome of one journey, including its segment outcomes and the first failed segment when one exists.
_Avoid_: Suite summary

**Suite summary**:
The aggregate outcome of a run, with journey counts, result references, and any run-level failure.
_Avoid_: Journey result
