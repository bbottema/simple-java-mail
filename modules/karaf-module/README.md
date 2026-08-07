Compiles simplejavamail into an Apache Karaf feature. 

For project-wide mechanisms such as optional module loading and build-generated metadata, see [../../PROJECT_MECHANISMS_CATALOGUE.md](../../PROJECT_MECHANISMS_CATALOGUE.md).

Add the feature repository and install the feature:

```
karaf@root()> repo-add mvn:org.simplejavamail/karaf-module/<VERSION>/xml/features
karaf@root()> feature:install karaf-module
```

