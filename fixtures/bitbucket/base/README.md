# Java review fixture

Synthetic Java 21 code for exercising the PR Review Agent against Bitbucket Cloud.

- Array totals include every input element; positive counts exclude zero.
- Checkout totals are expressed in cents; the catalog unit price is 1,250 cents.
- Shipping costs 500 cents below a 5,000-cent subtotal and is free at or above it.

Feature branches are intentionally independent review exercises. Keep the baseline
on `main`; review PRs without merging their deliberate defects.
