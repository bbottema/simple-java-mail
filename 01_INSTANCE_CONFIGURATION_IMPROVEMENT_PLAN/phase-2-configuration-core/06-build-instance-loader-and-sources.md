# Step 6: Replace global loading with ordered instance sources

- Status: Done
- Depends on: Steps 4 and 5
- Primary module: `core-module`
- Primary public types: instance `ConfigLoader`, `ConfigSource`, loader builder

## Goal

Build a pure resolver that snapshots ordered sources into a new `SimpleJavaMailConfig` without reading or writing process-global Simple Java Mail state.

## Tests first

1. Port the Step 2 table to injected maps and deterministic fake sources.
2. Assert later-source-wins behavior across three or more named custom sources.
3. Assert the conventional helper assembles classpath, environment, and system sources in the documented order.
4. Assert a missing classpath resource does not skip later sources.
5. Assert source maps are read once per `load()` and copied.
6. Assert loading twice yields independent snapshots and can observe deliberate changes in a source between loads.
7. Assert an existing snapshot can be used as a low-priority source for replacement or additive migration.
8. Assert strict supplied sources reject unknown keys while environment/system sources ignore unrelated keys.
9. Assert wildcard resolution and ordinary resolution share one merge model.
10. Assert caller streams are closed according to the Step 3 decision, including malformed input.
11. Assert a caller can supply an explicit ClassLoader for classpath resources.
12. Run parallel loads with different sources and assert zero cross-talk.

## Implementation

1. Define a small public `ConfigSource` contract with a diagnostic name and raw key/value snapshot.
2. Add built-in sources for `Properties`, maps, streams, classpath resources, environment variables, system properties, and an existing config snapshot.
3. Make source ordering explicit in the loader builder. Do not use numeric priorities or hidden source insertion.
4. Normalize blank values before choosing winners.
5. Merge raw values first, then parse only winners through the Step 4 schema.
6. Centralize wildcard key collection and provenance.
7. Return a new `SimpleJavaMailConfig` on every load.
8. Keep the legacy static API temporarily isolated for old runtime paths. It must not be used by the new loader.

## Diagnostics

Debug output may state that a key came from a named source or that a higher-priority blank was skipped. Values are only included for non-secret descriptors when explicitly useful. Custom source `toString()` output is never logged blindly.

## Acceptance criteria

- [x] The instance loader contains no mutable static field.
- [x] Custom source ordering is deterministic and documented as later source wins.
- [x] The conventional order matches 9.x.
- [x] Wildcard maps, typed objects, blank values, and unknown-key policies pass the Step 2 matrix.
- [x] Parallel and repeated loads produce independent snapshots.
- [x] No test modifies the private state of `ConfigLoader` through reflection.
- [x] The old static API and new instance API are clearly separated pending Step 15.

## Completion evidence

- `ConfigSource` and instance `ConfigLoader` support Properties, maps, streams, classpath resources, environment variables, system properties, and existing snapshots in explicit order.
- Fourteen focused instance-loader tests cover independent repeated and parallel loads, strict caller sources, tolerant process sources, input ownership, explicit ClassLoaders, and wildcard provenance.
- The temporary static API was subsequently removed in Step 15.
