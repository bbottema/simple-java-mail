Compiles simplejavamail into an Apache Karaf feature. 

For optional module boundaries, see [ADR 0006](../../docs/adr/0006-optional-modules.md). For project builds and generated CLI metadata, see [Developer Environment Setup](../../DEVELOPMENT.md).

Add the feature repository and install the feature:

```
karaf@root()> repo-add mvn:org.simplejavamail/karaf-module/<VERSION>/xml/features
karaf@root()> feature:install karaf-module
```

