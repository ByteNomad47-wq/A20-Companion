# Clean publication copy

This repository starts with fresh history and uses the maintainer's GitHub
no-reply address. Previous development history, archived APKs, raw device reports,
vendor firmware and private signing files are excluded.

The app namespace and documented automation intents use
`io.github.bytenomad47.a20`. This installs separately from earlier private
previews; their settings and device association do not migrate automatically.
Do not publish older APKs with this edition: they use a different application ID.

Source checks found no previous-account references, personal email addresses,
checked credential patterns or A6611 serial-number patterns. Image metadata
contained only colour-profile information. Such checks reduce disclosure risks;
they cannot guarantee anonymity or detect every possible secret.

The renamed source passed 134 unit/UI tests. Product-image redistribution rights
remain undocumented; see [third-party notices](../THIRD_PARTY.md).
