# Code Clarity Style Guide: Narrative Orchestration & Intent-Revealing Design

April 1st, 2026 – Comprehensive Coding Style Guide, compiled by Benny Bottema (AI-assisted)

This guide has two layers:

1. Narrative orchestration: how to structure functional flow at the top level.
2. Supporting clarity rules: how to keep lower-level code equally readable when drilling down.

## Purpose

This guide describes a coding style for writing and refactoring software so that the functional flow of a process is immediately readable, while technical implementation details remain discoverable by drilling down into lower-level methods and collaborators.

The goal is not to create more abstraction. The goal is to make complex behavior understandable.

Good code should let a reader start at the top and answer:

1. What process is being executed?
2. Why are these steps happening in this order?
3. Where do I drill down if I need the technical details?

This style is especially useful for application services, use cases, workflows, orchestration code, migration logic, domain processes, data processing pipelines, integration flows, and complex UI/application behavior.

Examples use Java and Kotlin, but the principles are language-agnostic.

---

## Core Principle

> Keep the top level at the level of intent. Push mechanics downward. Extract new concepts only when they earn a meaningful name.

Or shorter:

> Narrative at the top, mechanics at the leaves.

The top-level code should read like a functional process, not like a technical accident.

---

## Drill-Down Levels

Aim for code that can be read in layers:

1. **Process level** — what business or functional flow is happening?
2. **Sub-flow level** — how is one process step decomposed?
3. **Decision/boundary level** — where are rules, IO, mapping, and persistence handled?
4. **Technical level** — how is the concrete mechanism implemented?

Each layer should be coherent on its own. A reader at the process level should not have to think about HTTP headers; a reader at the technical level should not have to reconstruct the business intent from low-level mechanics. Reviewers and coding agents can use these layers as a checklist: when a method mixes layers, push the lower-layer detail downward until each method belongs to exactly one.

---

## Desired Shape

A good top-level method should look like a readable process narrative:

```java
public Decision assessApplication(ApplicationCommand command) {
    var application = receiveApplication(command);

    validateApplication(application);
    enrichApplication(application);
    determineEligibility(application);
    createDecision(application);
    persistDecision(application);
    publishDecisionMade(application);

    return application.decision();
}
```

The reader should be able to understand the process without seeing database queries, HTTP clients, JSON parsing, framework plumbing, retry loops, mapping details, or low-level conditionals at this level.

Those details belong lower down:

```java
private void enrichApplication(Application application) {
    customerProfileEnricher.enrich(application);
    riskProfileEnricher.enrich(application);
    existingContractEnricher.enrich(application);
}
```

And only at the leaves should the code become technical:

```java
class CustomerProfileEnricher {
    void enrich(Application application) {
        var response = customerClient.fetchCustomer(application.customerId());
        var profile = customerProfileMapper.toProfile(response);
        application.attachCustomerProfile(profile);
    }
}
```

---

## Vocabulary

This style may be referred to as:

* Narrative orchestration
* Intention-revealing decomposition
* Process-aligned code
* Top-down functional flow
* Intent-first implementation

These names are less important than the discipline behind them.

---

## Part I — Narrative Orchestration

### Greenfield Guidelines

#### 1. Start with the Process Spine

Before writing the technical implementation, sketch the functional flow as method calls.

```java
public Result handle(Command command) {
    var context = initialize(command);

    validate(context);
    enrich(context);
    decide(context);
    persist(context);
    publishOutcome(context);

    return context.result();
}
```

During design or refactoring, it is acceptable to sketch this shape before filling in the implementation. Before committing or handing over the change, the narrative skeleton must be backed by working implementation and tests where appropriate.

The process spine should be written in the language of the domain or functional design, not the language of frameworks.

Prefer:

```java
validateApplication(application);
determineEligibility(application);
publishDecisionMade(application);
```

Avoid:

```java
callEligibilityApi(application);
sendToDecisionQueue(application);
```

The first version tells the reader what the process means. The second only describes vague technical actions. Technical names are fine at boundary-level, but not as replacement for process-semantics at orchestration-level.

---

#### 2. Keep One Level of Abstraction per Method

A method should either describe a flow or implement one step of that flow.

Avoid mixing business-level steps with technical mechanics:

```java
public void process(Application application) {
    validateApplication(application);

    var statement = connection.prepareStatement("select * from customer where id = ?");
    statement.setString(1, application.customerId());
    var resultSet = statement.executeQuery();

    determineEligibility(application);
    publishDecisionMade(application);
}
```

The SQL detail breaks the abstraction level of the method.

Prefer:

```java
public void process(Application application) {
    validateApplication(application);
    loadCustomerProfile(application);
    determineEligibility(application);
    publishDecisionMade(application);
}
```

Technical implementation is then moved into `loadCustomerProfile` or a dedicated collaborator.

---

#### 3. Extract Methods Before Extracting Classes

Do not start by creating a framework of classes, interfaces, strategies, steps, or handlers.

Start by naming the functional parts of the process.

```java
validateApplication(application);
enrichApplication(application);
determineEligibility(application);
recordDecision(application);
notifyInterestedParties(application);
```

Only extract a class when a group of methods forms a cohesive concept.

Good reasons to extract a class:

* The concept has a meaningful domain or technical boundary name.
* The behavior is reused.
* The behavior deserves isolated testing.
* The behavior owns a real dependency, such as an external client, repository, mapper, or policy.
* The current class is becoming responsible for too many different things.

Poor reasons to extract a class:

* To make the top-level file look smaller.
* To hide technical ugliness without improving structure.
* To create theoretical future flexibility.
* To follow a pattern mechanically.

---

#### 4. Extract Interfaces Only When They Represent a Real Boundary

Interfaces are not required for every collaborator.

Avoid this by default:

```java
interface ApplicationValidator {
    void validate(Application application);
}

class DefaultApplicationValidator implements ApplicationValidator {
    public void validate(Application application) {
        ...
    }
}
```

Use an interface when there are multiple implementations, a meaningful architectural boundary, or a real need to decouple from infrastructure.

Good interface candidates:

* External service gateways
* Persistence boundaries
* Plugin points
* Runtime-selectable behavior
* Cross-module contracts

Bad interface candidates:

* Every service class by default
* Internal helper classes
* Artificial strategy objects with only one implementation
* Code created for speculative flexibility

---

#### 5. Prefer Named Collaborators Over Generic Step Objects

Do not turn every workflow into a list of generic steps.

Avoid over-engineered step pipelines unless the process is genuinely dynamic, configurable, or independently composable.

Avoid:

```java
workflow.execute(context);
```

when the real process is now hidden in wiring, configuration, dependency injection order, or a list of anonymous step implementations.

Prefer explicit process flow:

```java
validateApplication(context);
enrichApplication(context);
determineEligibility(context);
persistDecision(context);
publishDecision(context);
```

Step objects are acceptable when:

* The order is configurable.
* Steps are enabled or disabled by configuration.
* Multiple workflows reuse different combinations of the same steps.
* The workflow is naturally modeled as a pipeline.
* Observability, retries, compensation, or auditing are uniformly applied per step.

If none of these are true, explicit method calls are usually clearer.

---

#### 6. Use Context Objects Carefully

A context object can make a process easier to read when many steps share evolving state.

```java
public Result process(Request request) {
    var context = ProcessingContext.from(request);

    validate(context);
    enrich(context);
    decide(context);
    persist(context);

    return context.result();
}
```

Use a context object when it represents actual process state.

Avoid using a context object as a dumping ground for unrelated variables.

A good context object:

* Has a clear lifecycle.
* Belongs to one process.
* Contains state that is meaningful to that process.
* Makes method signatures simpler without hiding important dependencies.

A bad context object:

* Contains everything because passing parameters became annoying.
* Is mutated unpredictably by many unrelated classes.
* Makes it hard to see which step produces or consumes which data.
* Becomes a global variable in disguise.

When using a mutable context, prefer clear method names that reveal state transitions:

```java
enrichWithCustomerProfile(context);
attachRiskAssessment(context);
recordEligibilityDecision(context);
```

A context object should not become a way to hide data dependencies between steps. When everything is passed via context, it stops being visible which step produces which value and which step consumes it — readers (and coding agents) can no longer tell, from the signatures alone, what each step actually needs.

If a step only needs one or two values, prefer passing those values explicitly. Use the context when the step genuinely participates in the process state.

Prefer:

```java
var customerProfile = loadCustomerProfile(application.customerId());
var riskAssessment = assessRisk(customerProfile);
```

Avoid:

```java
loadCustomerProfile(context);   // reads context.customerId, writes context.customerProfile
assessRisk(context);            // reads context.customerProfile, writes context.riskAssessment
```

The second form looks tidy but turns the context into an implicit data bus. Each step's real inputs and outputs are no longer recoverable from the call site.

---

#### 7. Make IO Boundaries Obvious

Technical IO should be easy to find and should not pollute the functional flow.

Typical IO collaborators:

* Repository
* Gateway
* Client
* Publisher
* Listener
* Adapter
* FileReader / FileWriter
* ApiClient

Example:

```java
class DecisionWorkflow {
    private final CustomerGateway customerGateway;
    private final DecisionRepository decisionRepository;
    private final DecisionPublisher decisionPublisher;

    Decision decide(Application application) {
        enrichWithCustomerData(application);
        determineDecision(application);
        persistDecision(application);
        publishDecisionMade(application);

        return application.decision();
    }
}
```

The top-level flow remains functional, but IO boundaries are visible through collaborator names.

---

#### 8. Separate Business Decisions from Technical Plumbing

A method that determines a business decision should not also perform persistence, logging, remote calls, or framework work.

Avoid:

```java
private Decision determineEligibility(Application application) {
    var customer = customerClient.fetch(application.customerId());
    var score = riskApi.calculate(customer);

    if (score > 700 && application.amount().isLessThan(MAX_AMOUNT)) {
        decisionRepository.save(APPROVED);
        auditLogger.log("Approved");
        return APPROVED;
    }

    decisionRepository.save(REJECTED);
    auditLogger.log("Rejected");
    return REJECTED;
}
```

Prefer:

```java
private void determineEligibility(Application application) {
    var eligibility = eligibilityPolicy.determineFor(application);
    application.recordEligibility(eligibility);
}

private void persistDecision(Application application) {
    decisionRepository.save(application.decision());
}
```

This keeps decision logic explainable and testable.

---

#### 9. Stop Decomposing When the Code Is Already Obvious

Decomposition is a tool, not a goal. Do not extract a method merely to reduce line count. A short, linear block of obvious code may be clearer inline than behind another name.

Often fine inline:

```java
var fullName = firstName.trim() + " " + lastName.trim();
```

Not necessarily better:

```java
var fullName = buildFullName(firstName, lastName);
```

…unless `buildFullName` carries domain rules (locale ordering, honorifics, suffix handling) that deserve a name.

Extract when the new method earns its name by:

* Encoding a domain rule that has its own identity (`normalizePhoneNumber`, `applyVatRate`).
* Replacing a non-trivial computation that recurs.
* Hiding a multi-step sequence whose details would distract from the surrounding flow.
* Allowing a meaningful test in isolation.

Do not extract when:

* The body is one obvious expression.
* The extracted name would merely paraphrase the code (`addOne`, `concatenateStrings`).
* The reader has to jump to the helper to confirm it does exactly what the inline version did.

If the inline code already reads as the thing it is, leave it inline.

---

### Refactoring Existing Code

Large technical methods should be refactored by first recovering their narrative spine.

Do not start by designing the final class structure. Start by making the existing behavior readable.

---

#### 1. Protect Existing Behavior First

Before refactoring, add characterization tests where possible.

These tests do not need to be beautiful. Their purpose is to pin down current behavior so the code can be safely reshaped.

When tests are difficult, use a combination of:

* Golden master output tests
* Input/output examples
* Approval tests
* Log-based assertions
* Database state assertions
* Contract tests around external boundaries

Do not perform large structural refactors without some safety net unless the code is low-risk or already broken beyond repair.

---

#### 2. Annotate the Existing Method with Functional Comments

Take the large method and identify the real process steps.

```java
public Result process(Request request) {
    // normalize input
    // validate request
    // load existing customer state
    // enrich request with external data
    // determine applicable products
    // calculate price
    // save decision
    // notify downstream systems
}
```

These comments are temporary scaffolding. They are not the final design.

---

#### 3. Turn Functional Comments into Methods

Convert the comments into method calls.

```java
public Result process(Request request) {
    var input = normalizeInput(request);

    validateRequest(input);
    var customerState = loadExistingCustomerState(input);
    var enrichedInput = enrichWithExternalData(input, customerState);
    var products = determineApplicableProducts(enrichedInput);
    var price = calculatePrice(enrichedInput, products);
    var decision = saveDecision(enrichedInput, price);
    notifyDownstreamSystems(decision);

    return toResult(decision);
}
```

At this stage, the extracted methods may still contain ugly code. That is acceptable.

The first objective is to recover the top-level process.

---

#### 4. Normalize Abstraction Levels

After extraction, inspect the top-level method.

Every line should be at roughly the same level of abstraction.

Avoid:

```java
validateRequest(input);
var headers = new HttpHeaders();
headers.add("Authorization", tokenProvider.getToken());
var response = restTemplate.exchange(...);
determineApplicableProducts(input);
```

The HTTP details do not belong at the same level as the functional process.

Replace with:

```java
validateRequest(input);
loadCustomerProfile(input);
determineApplicableProducts(input);
```

---

#### 5. Refactor Extracted Methods from the Inside Out

Once the top-level flow is readable, improve each extracted method separately.

For each extracted method, ask:

* Is this method still doing multiple functional things?
* Does it mix business logic with IO?
* Does it hide a cohesive concept that deserves a class?
* Does it contain technical mechanics that belong in a gateway, repository, mapper, or utility?
* Is the method name still honest?

Repeat the same narrative-decomposition style recursively.

---

#### 6. Let Concepts Emerge Before Creating Classes

After method extraction, clusters will become visible.

Example cluster:

```java
loadCustomerProfile(context);
loadExistingContracts(context);
loadPaymentHistory(context);
```

This may become:

```java
customerContextEnricher.enrich(context);
```

Another cluster:

```java
calculateBasePrice(context);
applyDiscounts(context);
applyRiskSurcharge(context);
```

This may become:

```java
priceCalculator.calculate(context);
```

Extract classes when the code reveals stable concepts, not before.

---

#### 7. Use a Quarantine Delegate as a Transitional Tool

Sometimes a large method is too technical to cleanly decompose in one pass. In that case, it can be acceptable to move technical implementation into a temporary delegate so the orchestration can be clarified first.

```java
class DecisionWorkflow {
    private final DecisionTechnicalDelegate technical;

    Decision decide(Application application) {
        technical.validateApplication(application);
        technical.enrichApplication(application);
        technical.determineEligibility(application);
        technical.persistDecision(application);
        technical.publishDecisionMade(application);

        return application.decision();
    }
}
```

This is acceptable as a transitional move.

However, do not stop here if the delegate is merely a basement for unrelated technical code.

The desired direction is:

```java
class DecisionWorkflow {
    private final ApplicationValidator validator;
    private final ApplicationEnricher enricher;
    private final EligibilityPolicy eligibilityPolicy;
    private final DecisionRepository decisionRepository;
    private final DecisionPublisher decisionPublisher;

    Decision decide(Application application) {
        validator.validate(application);
        enricher.enrich(application);
        var eligibility = eligibilityPolicy.determineFor(application);
        var decision = application.createDecision(eligibility);
        decisionRepository.save(decision);
        decisionPublisher.publishDecisionMade(decision);
        return decision;
    }
}
```

A quarantine delegate is a bridge, not a destination.

---

#### 8. Avoid Synthetic Superclasses for Hiding Technical Detail

Do not use inheritance merely to hide technical methods from the readable top-level class.

Avoid:

```java
class DecisionWorkflow extends DecisionTechnicalBase {
    Decision decide(Application application) {
        validateApplication(application);
        enrichApplication(application);
        determineEligibility(application);
        persistDecision(application);
        publishDecisionMade(application);
        return application.decision();
    }
}
```

This makes the top-level class look clean, but usually creates hidden coupling.

Problems with this approach:

* Dependencies are hidden.
* Method origins are unclear.
* Inheritance is used for organization rather than polymorphism.
* Testing becomes harder.
* The superclass often becomes a junk drawer.
* Readers must navigate inheritance to understand composition.

Use composition instead:

```java
class DecisionWorkflow {
    private final ApplicationValidator validator;
    private final ApplicationEnricher enricher;
    private final DecisionRecorder recorder;

    Decision decide(Application application) {
        validator.validate(application);
        enricher.enrich(application);
        recorder.record(application);
        return application.decision();
    }
}
```

Inheritance is acceptable for a real Template Method pattern, where subclasses intentionally customize specific steps of a stable algorithm. It is not a good tool for hiding implementation detail.

---

### Class Design Guidelines

#### 1. Use Orchestrators for Use Cases and Workflows

An orchestrator coordinates a process. It should contain little technical detail.

```java
class RegisterCustomerUseCase {
    RegistrationResult register(RegisterCustomerCommand command) {
        var registration = receiveRegistration(command);

        validate(registration);
        ensureCustomerDoesNotExist(registration);
        createCustomer(registration);
        sendWelcomeMessage(registration);

        return registration.result();
    }
}
```

An orchestrator may depend on validators, policies, repositories, clients, mappers, and publishers.

It should not become the owner of all their implementation details.

---

#### 2. Use Policies for Business Decisions

A policy encapsulates a business decision. Depending on the codebase, this may be named Policy, Rule, Specification, EligibilityChecker, or DecisionService. The name matters less than keeping the decision isolated from IO.

```java
class EligibilityPolicy {
    Eligibility determineFor(Application application) {
        if (application.hasBlockingDebt()) {
            return Eligibility.rejected("Blocking debt");
        }

        if (application.income().isBelowMinimum()) {
            return Eligibility.rejected("Income below minimum");
        }

        return Eligibility.approved();
    }
}
```

Policies should be easy to test and should avoid technical IO.

---

#### 3. Use Gateways or Clients for External Systems

External systems should be represented by explicit boundaries.

```java
class CustomerGateway {
    CustomerProfile fetchCustomerProfile(CustomerId customerId) {
        var response = customerApi.getCustomer(customerId.value());
        return mapper.toCustomerProfile(response);
    }
}
```

The rest of the code should not know about HTTP details, headers, API-specific DTOs, retry configuration, or serialization mechanics unless it is part of the boundary implementation.

---

#### 4. Use Repositories for Persistence Boundaries

Persistence should not leak into the process narrative.

Prefer:

```java
decisionRepository.save(decision);
```

Avoid embedding persistence mechanics in orchestration code.

---

#### 5. Use Mappers and Assemblers for Translation

Mapping is often noisy and should not dominate functional flow.

Use dedicated mappers when translating between:

* API DTOs and domain objects
* Database entities and domain objects
* UI models and application commands
* External service responses and internal models

Keep mapping boring and isolated.

---

## Part II — Supporting Clarity Rules

The following rules support narrative orchestration by keeping lower-level code equally readable and drill-down friendly.

### Naming Guidelines

#### 1. Name Methods by Intent, Not Mechanics

Prefer:

```java
loadCustomerProfile(application);
determineEligibility(application);
recordDecision(application);
publishDecisionMade(application);
```

Avoid:

```java
callCustomerApi(application);
runRules(application);
save(application);
sendMessage(application);
```

Technical names are acceptable at technical levels. At the orchestration level, names should express process meaning.

---

#### 2. Use Verbs for Process Steps

Good process-step names usually start with strong verbs:

* validate
* enrich
* determine
* calculate
* create
* record
* persist
* publish
* notify
* resolve
* assemble
* transform
* apply
* reject
* approve

Avoid weak, vague verbs:

* handle
* process
* do
* manage
* perform
* execute
* run

These can be acceptable at entry points, but not as a substitute for meaningful process steps.

---

#### 3. Be Honest About Side Effects

A method name should reveal when it mutates state, persists data, calls external services, or publishes events.

Avoid:

```java
createDecision(application);
```

if the method also saves to the database and publishes an event.

Prefer:

```java
createDecision(application);
persistDecision(application);
publishDecisionMade(application);
```

Or, if they must be grouped:

```java
recordDecisionAndNotifyConsumers(application);
```

The grouped name is less elegant, but more honest.

---

#### 4. Be Suspicious of Generic Helper Names

Be suspicious of names like:

```java
Helper
Util
Manager
Processor
Handler
Service
```

These names are sometimes unavoidable, but they are often signs that the concept has not been understood yet.

Prefer names that describe responsibility:

```java
EligibilityPolicy
CustomerProfileEnricher
DecisionPublisher
ApplicationValidator
PriceCalculator
ContractRepository
PaymentHistoryGateway
```

`Service` is acceptable when the domain concept is genuinely service-like or when project conventions require it, but it should not be used to avoid naming the responsibility.

---

#### 5. Use Full, Intention-Revealing Names

Use full, intention-revealing names. Avoid private or ambiguous abbreviations.

Common domain or industry acronyms are acceptable when they are more recognizable than their expanded form, such as `HTTP`, `URL`, `DTO`, `API`, `UI`, `ID`, `JSON`, `SQL`, or `LLM`.

Avoid:

```kotlin
data class ScoredBranch(val wLlm: Double, val impNorm: Double, val combined: Double)
```

Prefer:

```kotlin
data class ScoredBranch(
    val weightedLlmScore: Double,
    /** Importance prior base min-max normalized within the current candidate pool (0–1). */
    val importanceNorm: Double,
    val combinedScore: Double,
)
```

The same rule applies to local variables. Single-letter names are acceptable only in tightly bounded lambdas or mathematical expressions where the variable matches conventional notation (e.g., `i`, `j` in index loops, `n` for a count).

**Exception — switch pattern variables:** Abbreviated binding variables in `case` clauses are acceptable when the abbreviation immediately follows the full type name on the same line and the scope is limited to that single case block. The type already carries the meaning; the binding is a handle, not a declaration.

Acceptable:
```java
case Foo f -> ...
case Bar b -> ...
```

The reader sees `Foo f` and understands `f` refers to the `Foo` variant. Expanding to `foo` adds length without adding meaning in this context and can in fact increase cognitive load.

---

#### 6. Name Generic Parameters by Role, Not Type

A parameter name like `working`, `list`, or `data` requires the reader to remember context from the call site.

Avoid:

```kotlin
private fun narrowByAdequacy(working: List<BranchResult>, failed: List<BranchResult>, ...)
```

Prefer:

```kotlin
private fun narrowByAdequacy(succeededBranches: List<BranchResult>, failed: List<BranchResult>, ...)
```

The name `succeededBranches` carries its filtering semantics into the body without requiring a comment.

---

### API Clarity

#### 1. Prefer Named Types Over Opaque Tuples for Non-Trivial Returns

`Pair`, `Triple`, and anonymous tuples are acceptable when the component semantics are self-evident at every call site.

They become opaque when:

* Components are the same type (e.g., two `Boolean`s, two `Int`s, two `String`s) and their meaning is not obvious from context.
* The caller must remember positional semantics by convention rather than by reading the code.
* The type crosses a method boundary more than once.

Bad:

```kotlin
// Caller: val (level, flat) = selectBranchLevel(summary) — what does the Boolean mean?
private fun selectBranchLevel(...): Pair<Int, Boolean>
```

Best — when the type is used in multiple methods, give it a name:

```kotlin
private data class BranchLevelChoice(val level: Int, val isFlat: Boolean)
```

For public or cross-module APIs, prefer a named return type. If project constraints temporarily force `Pair` or `Triple`, document every component in KDoc and treat it as technical debt:

```kotlin
/**
 * Returns a `Pair(level, isFlat)` where:
 * - `level`  is the chosen hierarchy level (0 = leaf-level fallback).
 * - `isFlat` is `true` when the heuristic fell back to level 0: no meaningful
 *   multi-level hierarchy, all scores below threshold, or < 3 communities.
 */
fun selectBranchLevel(...): Pair<Int, Boolean>
```

Triples are almost always worth naming:

```kotlin
// Before: Triple<String, List<Node>, String> — what are these three things?
// After:
private data class CombinedContextResult(
    val combinedText: String,
    val novelNodes: List<Node>,
    val supplementText: String,
)
```

---

#### 2. Promote Local Functions When They Hide Meaningful Operations

Avoid local functions inside already-complex methods. Promote them to private methods when they represent a named operation, are longer than a few lines, are reused, or would help readers scan the class.

Very small local lambdas or local helpers are acceptable when they genuinely improve locality and do not hide meaningful behavior.

Bad:

```kotlin
private fun hybridRaw(...): List<Hit> {
    fun sanitizeFullText(raw: String): String { ... }
    // ... 60 more lines ...
}
```

`sanitizeFullText` is invisible to readers scanning the class, cannot be independently tested, and forces readers to track nesting depth inside an already-complex method.

Better:

```kotlin
private fun hybridRaw(...): List<Hit> {
    val ft = sanitizeFullText(q)
    ...
}

private fun sanitizeFullText(raw: String): String { ... }
```

Acceptable: extremely short lambdas (`val isReady = { it.state == READY }`) or one-liner helpers that are genuinely not useful outside the parent method and do not obscure meaningful logic.

---

### Documentation

> **Golden rule: self-describing code over documenting code.**
> Reach for a better name, a named type, or a purer function before reaching for a comment.

Documentation is not the enemy of good code — misplaced documentation is.

#### Document the *why* and the *non-obvious constraint*, not the *what*

Good documentation explains things the code cannot:

* **Mathematical or algorithmic intent** — why this formula, not what the formula does.
* **Non-obvious parameter semantics** — especially for primitive parameters where the name alone is insufficient.
* **Business invariants** — rules that come from the domain, not from implementation.
* **Deliberate trade-offs** — why one approach was chosen over an obvious alternative.
* **Cross-cutting constraints** — e.g., thread-safety guarantees, idempotency requirements.

Bad (restates the code):

```kotlin
/** Adds 1 to the count. */
fun increment() { count++ }
```

Good (explains a non-obvious constraint):

```kotlin
/**
 * Liveness clamping guardrail: if the four primary semantic dimensions sum to < 4
 * (weak signal), the liveness indicator is zeroed to prevent it from inflating the
 * score of low-quality communities.
 */
fun weightedLlmScore(...): Double
```

#### When to add a KDoc

Add a KDoc when **at least one** of the following is true:

* The function is part of a public or cross-module API.
* The return type is a `Pair` or `Triple` whose components need explanation.
* The function has a non-obvious precondition, postcondition, or invariant.
* The function makes a deliberate algorithmic choice that is not visible from the name.
* A parameter name alone is insufficient to convey valid values or semantics.

Do **not** add a KDoc just to have one. A well-named method with clear parameters is better than a poorly-named method with a long comment.

#### Inline comments

Inline comments (`//`) are acceptable to:

* Explain a section boundary within a long-but-justified method (prefer section labels like `// ---- section name ----`).
* Call out a non-obvious invariant at the point of use.
* Warn about a subtle precondition that cannot be expressed in the type system.

They should not narrate what the code already says.

---

### Control Flow

#### 1. Keep Branching at the Same Level of Intent

Conditional logic is acceptable in orchestration code when the condition is part of the functional process.

Good:

```java
if (applicationRequiresManualReview(application)) {
    routeToManualReview(application);
    return application.reviewResult();
}

continueAutomatedAssessment(application);
```

Technical branching should be pushed downward.

Bad:

```java
if (response.statusCode() == 429 || response.statusCode() >= 500) {
    retryWithBackoff(response);
}
```

Better:

```java
customerGateway.fetchCustomerProfile(application.customerId());
```

The retry/status-code logic belongs inside the gateway, client, or retry policy. Narrative orchestration is not always linear, but each branch should be expressed at the same abstraction level as its surrounding steps.

---

#### 2. Control-Flow Maze Drain Loops

Avoid `while (true)` loops with multiple `break` / `continue` branches when the loop has a deterministic stop condition.

Bad:

```java
while (true) {
    if (!ready()) break;
    if (isEmpty()) {
        if (terminal()) break;
        continue;
    }
    advance();
}
```

Better:

```java
while (canAdvance()) {
    advanceNext();
}
```

Name the loop predicate with process intent (`canAdvance`, `canReleaseNextDepth`, etc.) so readers do not need to reconstruct loop semantics from control-flow jumps.

---

### Purity and Side Effects

#### 1. Keep Value-Producing Methods Pure

Methods named `render*`, `format*`, `to*`, or `build*` should be pure value-producing methods. They should return their value and avoid process-significant side effects.

Do not hide process-significant logging, metrics, persistence, publication, or shared-state mutation inside value-producing methods.

Low-level trace logging may be acceptable when it is purely diagnostic and does not obscure the process narrative, but important logging or metrics should happen at the caller/orchestration level. Diagnostic trace/debug logging is not considered process-significant when it only helps inspect local computation and has no business, audit, metric, or operational meaning.

If an `assemble*` method mutates an existing object, make the target and side effect explicit in the name:

```kotlin
// Pure
fun assembleCustomerViewModel(...): CustomerViewModel

// Mutating, but explicit
fun assembleIntoExistingApplication(application: Application, ...)
```

Bad:

```kotlin
private fun buildCombinedContext(...): String {
    val combined = ...
    log.info("combined={} novel={}", combined.length, novelNodes.size) // hidden side effect
    return combined
}
```

The caller that reads `val ctx = buildCombinedContext(...)` should not have to account for process-significant logging, metrics, persistence, publication, or shared-state mutation.

Better — return the data and let the caller log it:

```kotlin
private fun buildCombinedContext(...): CombinedContextResult { ... }

// In the caller:
val ctx = buildCombinedContext(outputs, allNodes, allEdges)
logCombineStats(outputs, ctx.combinedText, ctx.novelNodes, ctx.supplementText) // explicit
```

The same applies to `render*` methods: they should return `String` (e.g., via `buildString { ... }`), not accept a `StringBuilder` and mutate it.

Bad:

```kotlin
private fun renderNovelNodes(sb: StringBuilder, nodes: List<Node>) { sb.append(...) }
```

Prefer:

```kotlin
private fun renderNovelNodes(nodes: List<Node>): String = buildString { ... }
```

---

## Anti-Patterns

### 1. The Clean Shell Around a Dirty Basement

This happens when the top-level class looks beautiful, but all technical mess has simply been moved into one massive delegate.

```text
ReadableWorkflow
  -> EverythingTechnicalDelegate
```

This is acceptable as a temporary quarantine, but not as a final design.

The delegate should eventually split into cohesive concepts.

---

### 2. Ceremonial Decomposition

Avoid extracting methods that do not add meaning.

Bad:

```java
prepare();
process();
handleResult();
finish();
```

Better:

```java
normalizeInput();
loadCustomerContext();
determineAvailableProducts();
recordOfferDecision();
notifyCustomer();
```

Good decomposition compresses meaning. Bad decomposition merely hides lines.

---

### 3. Strategy Theater

Avoid introducing Strategy, Step, Handler, Command, or Pipeline objects for imaginary future flexibility.

Bad signs:

* There is only one implementation.
* The order is not actually configurable.
* The abstraction hides the process instead of revealing it.
* Understanding the workflow requires reading dependency injection configuration.
* The pattern exists because it feels architecturally neat, not because the code needed it.

Prefer direct, readable orchestration until variability is real.

---

### 4. Mixed Abstraction Methods

Avoid methods where high-level process steps are mixed with low-level mechanics.

Bad:

```java
validateInput();
var json = objectMapper.writeValueAsString(payload);
var response = httpClient.post(url, json);
calculateDecision();
```

Better:

```java
validateInput();
sendAssessmentRequest();
calculateDecision();
```

---

### 5. Hidden Side Effects

Avoid harmless-looking methods that secretly persist data, publish events, mutate global state, or call external systems.

Bad:

```java
var decision = determineDecision(application);
```

when `determineDecision` also saves and publishes.

Better:

```java
var decision = determineDecision(application);
saveDecision(decision);
publishDecisionMade(decision);
```

---

### 6. Utility Dumping Ground

Avoid moving technical details into generic utility classes unless the behavior is truly generic and stateless.

Bad:

```java
ApplicationUtils.calculateEligibility(...)
ApplicationUtils.saveDecision(...)
ApplicationUtils.publishEvent(...)
```

Better:

```java
EligibilityPolicy.determineFor(...)
DecisionRepository.save(...)
DecisionPublisher.publishDecisionMade(...)
```

Utilities should be rare and boring.

---

### 7. Source-Order Over Story Order

Avoid organizing methods by interface, declaration, or source order when it hurts readability.

A class should read in the order that helps a reader understand the process fastest.

For event/callback-heavy code, place handlers in reader-first narrative order (for example: "main signal", then "completion", then "setup/metadata") when that order clarifies the flow.

Declaration order is acceptable only when it does not reduce comprehension.

---

### 8. Opaque Tuple Returns

Avoid returning `Pair` or `Triple` when the component semantics are not recoverable from context.

Bad:

```kotlin
// Return is Pair<Int, Boolean> — caller sees: val (level, flat) = select(...)
// Is `flat` the level number? Is it an error flag? A direction?
private fun select(...): Pair<Int, Boolean>
```

Better (for internal use) — give the type a name:

```kotlin
private data class BranchLevelChoice(val level: Int, val isFlat: Boolean)
```

Acceptable fallback — if a constraint forces a `Pair`, document each component in KDoc and treat it as technical debt:

```kotlin
/**
 * Returns `Pair(level, isFlat)`:
 * - `level`  chosen hierarchy level (0 = leaf fallback).
 * - `isFlat` true when no meaningful multi-level hierarchy was found.
 */
fun select(...): Pair<Int, Boolean>
```

Triple returns are almost always worth naming immediately.

---

### 9. False Domain Language

Intent-revealing names must remain honest. A business-sounding method name should not be used to disguise unrelated technical work.

Bad:

```java
determineEligibility(application);
```

…when the method actually loads customer data, maps DTOs, saves a decision, publishes an event, and handles errors before returning.

This is worse than honest technical code because it creates a false narrative: the reader trusts the name, drills down expecting a business decision, and instead finds a tangle of unrelated mechanics.

Better — let the orchestration spine show what really happens, and reserve the domain name for the actual decision:

```java
loadCustomerProfile(application);
var eligibility = eligibilityPolicy.determineFor(application);
recordDecision(application, eligibility);
publishDecisionMade(application);
```

If a method name promises a business concept, the body should deliver that concept. If it cannot, rename the method or split it.

---

## Coding Agent Instructions

When modifying code in this codebase, coding agents must preserve or improve narrative orchestration.

### Required Behavior

When implementing new behavior:

1. Start by identifying the functional flow.
2. Keep the top-level method readable as a process narrative.
3. Push technical mechanics into lower-level methods or cohesive collaborators.
4. Prefer explicit method calls over generic pipelines unless configurability is real.
5. Keep side effects visible in method names or as separate process steps.
6. Order methods by reader-first narrative flow when ordering affects comprehension.
7. Apply the supporting clarity rules in Part II to lower-level code (naming, API shape, documentation, control flow, purity).
8. When unsure, prefer a smaller local improvement over a broad architectural rewrite.

### Refactoring Behavior

When refactoring large methods:

1. Preserve behavior first.
2. Recover the process spine using intention-revealing method names.
3. Extract methods before extracting classes.
4. Normalize abstraction levels.
5. Split cohesive collaborators only after patterns emerge.
6. Treat technical delegates as temporary quarantine, not final architecture.

### Agent Do Not List

Do not:

* Replace a readable sequence of calls with an opaque pipeline.
* Add interfaces for every class by default.
* Create Strategy, Step, Handler, or Command abstractions without real variation.
* Move all implementation into a generic technical helper.
* Use inheritance merely to hide methods.
* Mix business decisions with IO or framework plumbing.
* Rename methods to vague names like `process`, `handle`, `execute`, or `manage` unless they are true entry points.
* Order callbacks strictly by declaration/source order when that obscures the process narrative.
* Use `while (true)` + `break` / `continue` for deterministic drain loops that can be expressed with a named condition.
* Abbreviate field or variable names beyond well-known industry acronyms (`HTTP`, `URL`, `DTO`, `API`, `UI`, `ID`, `JSON`, `SQL`, `LLM`, etc.).
* Return `Pair` or `Triple` for types that require callers to remember positional semantics — name them, or document each component in KDoc as a temporary fallback.
* Nest `fun` declarations inside complex method bodies when a private method would aid discoverability.
* Give `render*`, `format*`, `to*`, or `build*` methods process-significant side effects. They should behave as value-producing methods.
* Add KDoc that merely restates what the code already says.
* **Implement canonical/shared graph semantics in JavaScript.** Changes to dependency evidence, shared structural visibility, cycle/governance classification, or report membership belong in Kotlin and an explicit model contract. Documented lens-local projections over prepared fields may stay in the frontend. See `docs/atlasarc/ARCHITECTURE.md §4`.
* **Call `window.renderGraph(lastVm)` directly from a JS toggle function.** Toggles that affect graph structure must call `sendFiltersAction()` instead, so Kotlin rebuilds the view model with the new filter state.
* **Change `FilterSet` defaults without checking JS defaults.** The two must agree or the first Analyze render will contradict the toolbar button's pressed state. See `docs/atlasarc/ARCHITECTURE.md §6`.

### Agent Review Checklist

Before finishing a change, verify:

* Can the top-level method be read as a functional process?
* Are all lines in the top-level method at the same abstraction level?
* Are technical details discoverable by drilling down, not exposed prematurely?
* Are side effects visible at the call site?
* Are new classes cohesive and meaningfully named?
* Are new interfaces justified by a real boundary?
* Did the change reduce cognitive load rather than merely moving complexity?
* Would a developer be able to place breakpoints along the functional flow and understand the state transitions?
* Do multi-component returns have a named type, or KDoc that explains each component?
* Is added documentation explaining *why* or a *non-obvious constraint*, not the *what*?
* **If the change involves the webview UI:** Was `docs/atlasarc/ARCHITECTURE.md` consulted? Is the change a documented lens-local projection, or has canonical/shared meaning leaked out of Kotlin?
* **If a new toolbar button was added:** Were all steps in `docs/atlasarc/ARCHITECTURE.md §5` completed? Was the button added to `atlasarc.io-plugin/ui/index.html`?
* **If a `FilterSet` default was changed:** Does the new default match the corresponding JS `localStorage` default so the first Analyze render is consistent?

---

## Human Code Review Checklist

Use these questions during review:

1. Can I explain the top-level flow to a non-technical stakeholder?
2. Does the code follow the functional design, activity diagram, or business process where one exists?
3. Are implementation details hidden at the right level, not hidden completely?
4. Do method names explain intent rather than mechanics?
5. Are side effects explicit?
6. Are classes extracted around cohesive concepts?
7. Is there any speculative flexibility?
8. Is there a generic helper, manager, or processor hiding an unnamed concept?
9. Does the code become more understandable when I drill down?
10. Would debugging this flow be straightforward?

---

## Practical Refactoring Example

### Before

```java
public Result process(Request request) {
    if (request == null || request.customerId() == null) {
        throw new ValidationException("Invalid request");
    }

    var customerResponse = customerClient.get("/customers/" + request.customerId());
    var customer = objectMapper.readValue(customerResponse.body(), CustomerDto.class);

    var contracts = contractRepository.findByCustomerId(request.customerId());
    var activeContracts = contracts.stream()
        .filter(contract -> contract.status() == ACTIVE)
        .toList();

    var eligible = customer.age() >= 18 && activeContracts.size() < 3;

    var decision = new Decision(request.customerId(), eligible);
    decisionRepository.save(decision);

    eventPublisher.publish(new DecisionMadeEvent(decision.id(), decision.eligible()));

    return new Result(decision.id(), decision.eligible());
}
```

### First Refactoring: Recover the Spine

```java
public Result process(Request request) {
    validateRequest(request);

    var customer = loadCustomer(request);
    var activeContracts = loadActiveContracts(request);
    var decision = determineDecision(request, customer, activeContracts);

    persistDecision(decision);
    publishDecisionMade(decision);

    return toResult(decision);
}
```

### Further Refactoring: Extract Cohesive Collaborators

```java
public Result process(Request request) {
    validateRequest(request);

    var customerContext = customerContextLoader.loadFor(request.customerId());
    var decision = eligibilityPolicy.determineDecision(request, customerContext);

    decisionRepository.save(decision);
    decisionPublisher.publishDecisionMade(decision);

    return resultMapper.toResult(decision);
}
```

This version is not just shorter. It exposes the process and gives each concept a place.

---

## Final Heuristics

Use these rules when unsure:

* If a method explains what happens, keep it high-level.
* If a method explains how one thing happens, keep it focused.
* If code mixes levels of abstraction, extract downward.
* If a concept has a name, consider giving it a class.
* If a class has no meaningful name, do not create it yet.
* If flexibility is only imagined, do not design for it.
* If implementation detail is hidden but not organized, keep refactoring.
* If a non-technical person can follow the top-level flow, the process narrative is probably healthy.
* If a technical person can drill down and find the mechanics where expected, the implementation structure is probably healthy.
* If a return type has more than one component and a caller must remember positional semantics, give the type a name or document each component.
* If a method is named `build*` or `render*` and it performs important logging, metrics, persistence, publication, or shared-state mutation, the side effect belongs in the caller.
* If a variable or field name is an unfamiliar abbreviation, spell it out — readability compounds across the whole codebase.
* If code cannot be made self-describing (mathematical formulae, domain invariants, deliberate trade-offs), add a focused KDoc or comment explaining the *why*.

The target is not minimal code.

The target is code that tells the truth at the right level.
