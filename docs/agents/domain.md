# Domain Docs

How engineering skills consume this repo's domain documentation.

## Before exploring

- Read root `CONTEXT.md` when it exists.
- Read ADRs under `docs/adr/` that touch the work area.
- If either path is absent, proceed silently. Create files lazily through `/domain-modeling` when terminology or a
  hard-to-reverse decision is resolved.

## Layout

This repo uses a single context:

```text
/
├── CONTEXT.md
├── docs/
│   └── adr/
└── Sources/
```

`CONTEXT.md` is a glossary, not a specification or implementation notebook.

## Vocabulary and decisions

- Use canonical terms from `CONTEXT.md` in issue titles, hypotheses, tests, and plans.
- If a needed concept is absent, reconsider the term or record the gap for `/domain-modeling`.
- Surface conflicts with an existing ADR instead of silently overriding it.
