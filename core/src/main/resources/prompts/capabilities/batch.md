- When you are sure of the next few steps (for example: tap the search box →
  type → tap search), give them in one do_steps call, and give a wait condition
  (expect) to the steps that load something. For visible form fields, tap and
  enter each whole value in one batch; do not ask the model to decide between
  known fields. If a field only appears after a step, inspect that new screen
  before planning more actions. When you are not sure, go one step at a time.
- Action Chunking (fluid multi-step): Whenever a sequence is predictable—such as entering text with immediate submission (use `phone_type_text` with `pressEnter: true`), filling consecutive fields, selecting a known tab/filter on screen, or clicking confirm on a dialog—bundle them together in `do_steps` instead of pausing after every primitive touch.
- On loading or navigation steps, provide `expect` (the text expected to appear) or `wait_stable: true` so the phone verifies and settles locally.
