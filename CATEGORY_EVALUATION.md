# Evaluate category suggestions

Category suggestions are transparent English text rules, not a trained or calibrated model. `CategoryClassifier.suggest()` exposes the leading score, its margin over the next category, and the matched phrases. It accepts a suggestion only at score **4 or more**, with a margin of **2 or more**. Weak words and close competing categories go to **Other / needs review**. Repeating one word does not increase its score. This intentionally trades automatic coverage for fewer unsupported suggestions. The score is not a percentage chance of correctness.

User category corrections still take precedence. Existing persisted categories are not silently rewritten by changing these rules; resetting to the current suggestion evaluates the stored text with the current rules.

## Collect a consented local sample

No consented screenshot corpus is included, and no real-world classification accuracy is claimed. Before changing more signals, obtain consent for a representative sample across all six categories, including ambiguous and low-text images. Include varied apps and naming conventions. Split rule-development and held-out evaluation samples; report sample counts, not only aggregate accuracy.

1. On the consented device, let on-device OCR finish. Record the **uncorrected suggestion**, not a user-corrected category. For existing corrected images, record the suggestion after using reset-to-suggested, then restore the user's choice if needed.
2. Independently assign the expected category using a written rubric. `OTHER` includes screenshots without enough reliable evidence, and content that does not belong to one of the five named categories. Resolve disagreement with the consenting owner; do not treat age or short text as uselessness.
3. Prepare a local CSV with just these columns:

   ```csv
   expected,suggested
   STUDY,STUDY
   RECEIPTS,OTHER
   ```

   These two rows are **fabricated format examples**, not measured results. JSONL objects with `expected` and `suggested` also work. Allowed values are `STUDY`, `RECEIPTS`, `SHOPPING`, `TICKETS`, `CHATS`, and `OTHER` (case insensitive).
4. Run:

   ```bash
   python3 tools/evaluate-categories.py /private/local-labels.csv \
     --consent-confirmed --output /private/category-report.json
   ```

The evaluator scores the suggestions actually recorded from SnapSort; it does not duplicate the Kotlin classifier in Python. It reads no screenshot bytes or OCR text, requires no account/network, and outputs only aggregate category counts and metrics. Keep manifests/reports outside the repository unless there is separate consent to publish them. It does not export image filenames or per-item records into reports.

The report includes a confusion matrix (actual rows, suggested columns), overall accuracy, accuracy among accepted named-category suggestions, the Other/needs-review fraction, and per-category recall. A large Other fraction can hide poor coverage even when accepted suggestions look accurate. Results describe the provided sample only.

## Other writing systems

The bundled OCR recognizer remains Latin-only. This change adds no Hindi model and claims no Hindi accuracy. Establish demand, obtain a consented Hindi sample, evaluate script/mixed-language OCR failures separately from category mistakes, and measure APK size and device performance before adding a recognizer.
