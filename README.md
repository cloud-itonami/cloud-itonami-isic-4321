# cloud-itonami-4321

Open Business Blueprint for **ISIC Rev.5 4321**: electrical installation
(licensed electrical trade work in buildings and structures).

This repository designs a forkable OSS business for community electrical
installation: licensed-electrician scope management, robotics-assisted
installation and inspection, and permit/energization records — run by a
qualified operator so an electrical contractor keeps its own permit and
inspection history instead of renting a closed compliance platform.

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot
performs the physical domain work**. Here robots (conduit/cable
installation, panel assembly, inspection) operate under an actor that
proposes actions and an independent **Electrical Trade Governor** that
gates them. The governor never energizes an installation itself;
`:high`/`:safety-critical` actions (energizing an installation that has
not passed inspection, any work outside a licensed electrician's
verified scope) require human sign-off.

## Core Contract

```text
intake + identity + design specification + permit
        |
        v
Trade Advisor -> Electrical Trade Governor -> permit, installation, inspection record, or human approval
        |
        v
robot actions (gated) + build record + inspection/energization record + audit ledger
```

No automated advice can energize an installation the governor refuses,
approve work outside a licensed electrician's verified scope, or publish
an inspection record without governor approval and audit evidence.

## Capability layer

Resolves via [`kotoba-lang/industry`](https://github.com/kotoba-lang/industry)
(ISIC `4321`). Implemented by:

- [`kotoba-lang/robotics`](https://github.com/kotoba-lang/robotics) — missions, actions, safety-stops, telemetry proofs
- [`kotoba-lang/cae-solver`](https://github.com/kotoba-lang/cae-solver) — computer-aided engineering simulation contracts (circuit/load calculations)

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md).

## License

AGPL-3.0-or-later.
