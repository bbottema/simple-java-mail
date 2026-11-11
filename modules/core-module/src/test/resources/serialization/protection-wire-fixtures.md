# Historical protection wire fixtures

The six `openpgp-details-selected-legacy-*.base64` files represent the earlier unreleased selected-signature aggregate. They remain loadable as opaque wire history after the supported API is replaced. Their old aggregate status cannot establish coverage of any current selected value.

`openpgp-details-occurrences-legacy-nonempty.base64` uses the same earlier unreleased core model with three signature-list entries, including a repeated instance and differing outcomes. Its synthetic public fingerprints and explanatory text are fixture data. It exercises the obsolete signature-entry class name as well as the old aggregate and enum names, so empty selected-only snapshots cannot hide a broken nested-object wire dependency.

Only package-private serialization shells retain those class names. They expose no old result API, builder, status getters or live processing model.
