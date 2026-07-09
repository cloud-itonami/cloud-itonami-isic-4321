# Business Model: Community Electrical Installation

## Classification
- Repository: `cloud-itonami-4321`
- ISIC Rev.5: `4321` — electrical installation
- Social impact: worker safety, fire safety, consumer protection

## Customer
- independent electrical contractors needing an auditable
  permit/inspection platform
- property owners and general contractors needing verifiable
  electrical-work records
- inspection authorities needing verifiable energization records
- programs that cannot accept closed, unauditable permit platforms

## Offer
- licensed-electrician scope and permit management
- robotics-assisted installation and inspection
- build and design-specification records
- permit and energization/inspection records
- role-based access and immutable audit ledger

## Revenue
- self-host setup fee
- managed hosting subscription per contract/site
- support retainer with SLA
- installation/inspection robot integration and maintenance

## Trust Controls
- a robot action the governor refuses is never dispatched
- safety-critical actions (energizing an installation that has not
  passed inspection, work outside a licensed electrician's verified
  scope) require human sign-off
- an installation cannot be energized outside its verified inspection
  scope
- inspection and energization records require source verification
  evidence
- sensitive customer and site data stays outside Git
