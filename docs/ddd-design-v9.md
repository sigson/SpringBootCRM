# Design Document v9: Production-Ready DDD-архитектура на Spring Boot

> **Целевая платформа:** Java **21**, Spring Boot **3.4.8**, Spring Cloud **2024.0.x**, Hibernate **6.6+**, PostgreSQL 15+, Axon 4.10, Temporal 1.27.
> **Применимо** как для модульного монолита, так и для распределённой системы.
> Документ декларативный и самодостаточный — описывает архитектуру от и до без оглядки на существующую кодовую базу.

> **Ключевые изменения v9 относительно v8 — 10 закрытых дыр:**
>
> - **G1.** Hibernate listener'ы регистрируются через свойство `hibernate.integrator_provider` (`IntegratorProvider`-bean), а **не** через ошибочный ключ `hibernate.session_factory.statement_inspector`. Окно «без access-checks» закрыто фактически, не только декларативно. Контракт проверяется `AccessListenerRegistrationIT`-тестом (см. §15.2).
> - **G2.** `RedisCacheManager` использует **разные** `ObjectMapper`'ы per-namespace: `outboxObjectMapper` (без access-modifier'а) для `aggregate`/`dto`/`page`/`mapping-plan`, `accessAwareObjectMapper` — только для `projection`-namespace. Устранена возможность data-corruption при cross-user cache write (см. §21.1).
> - **G3.** `AggregateReferenceXxxUserType`-семейство переписано: mapping-classes реально аннотированы `@Embeddable`, `CompositeUserType` использует корректные сигнатуры Hibernate 6 (`disassemble`/`assemble`/`replace` принимают `SharedSessionContractImplementor`); неиспользуемое поле `idSqlType()` удалено. Внешний API (`AggregateReferenceUuidUserType`, ..., `AggregateReferenceTsidUserType`) сохранён, новых типов не вводится (см. §11.2).
> - **G4.** `PostLoadAccessCheckListener` имеет два режима: `THROW` для single-row (`findById`/`findByIdLocked`) и `MARK_AND_DROP` для коллекционных загрузок (`findAll`/`Page<T>`). Режим устанавливается AOP-аспектом через `ThreadLocal<PostLoadMode>`. `AccessAwarePage` корректно выставляет `approximateTotal=true`, когда было хоть одно `MARK_AND_DROP`-усечение. Метрика `ddd.postload.dropped{typeId}` (см. §13.7, §22.3).
> - **G5.** `FilteredCountQuery` переписан на `CriteriaBuilder` — параметр `additionalSpec` больше не игнорируется, total пагинации соответствует фактическому фильтру + access-предикатам. Native SQL и связанная с ним SQL-identifier-валидация исключены из hot-path'а (см. §22.2).
> - **G6.** `AccessLevel` имеет канонический инвариант: `flags` всегда хранится в expanded-форме (после `AccessFlags.expand`). Compact-конструктор record'а нормализует значение. `equals`/`hashCode`/`toString`/`intersect`/`union` работают консистентно (см. §4.3).
> - **G7.** Liquibase разделён на 2 стадии: schema-only (DDL до Spring DI) и `DataSeedRunner` (data-seed после `MetadataSnapshot.afterSingletonsInstantiated`). ArchUnit запрещает `CustomTaskChange` для data-seed'а (см. §7.5, §24.8).
> - **G8.** `UserAccessContextLoader.loadFor` использует `user.getAccess()` напрямую и догревает `CaffeineUserAccessProvider` через `warmCache(...)` — двойная загрузка `UserAggregate` устранена (см. §7.4).
> - **G9.** `AccessProjectionFingerprint` per-instance: если у пользователя для целевого `typeId` есть **непустой** `instanceWriteAcl`-grant (per-instance ACL), fingerprint включает `instanceId` + `principalIdRaw` — корректность для сценариев с per-instance permissions; для типов без instance-ACL fingerprint остаётся cross-user shareable (см. §19.2).
> - **G10.** `@ElementCollection` field-level access описан явно: `@FieldId.defaultAccess` на самом collection-поле контролирует видимость целиком, `@FieldId` на полях `@Embeddable`-элемента — содержимое; `AccessAwareEmbeddableWriter.serializeCollectionAsField` push'ит collection-fieldId в path-stack перед итерацией (см. §10.6, §12.5). Дополнительно: `AccessAwareReportingService` для агрегационных запросов (`SUM`/`COUNT`/`GROUP BY`) с access-фильтрацией через тот же `CriteriaBuilder`-путь (см. §22.5).
>
> **Сохранены без изменений из v8 (F1–F21):** все ключевые решения, отмеченные в v8 как закрытые дыры. Этот документ — самодостаточный; сравнение с v8 приводится только для контекста.

---

## 0. Карта решений

| № | Требование | Готовое решение | Кастом |
|---|---|---|---|
| 1 | Bean Validation | Hibernate Validator (Jakarta 3); `validation.mode=none` на prod, `callback` в test profile | Явный `Validator.validate` в сервисном слое; `AccessAwarePreUpdateListener`/`PreInsertListener` через `IntegratorProvider`-bean (G1); enforcement через **интеграционные тесты** |
| 2 | Распределённые TX + идемпотентность | **Axon Saga (state-stored)** в той же БД (события не в Axon EventBus, а только saga-state); **Temporal Workflow** для долгих процессов | `IdempotencyGuard` с Redis SETNX + **Redis Streams** + **renewable lock** через `ScheduledExecutorService`; saga подписывается на **Kafka topic'и** через SCS consumer |
| 3 | Брокер ↔ логика | **Spring Cloud Stream (functional)** + Kafka binder | `OutboxPoller` с **per-message транзакциями**, `SELECT FOR UPDATE SKIP LOCKED`; `DomainEventRegistry` со стабильными именами; **partition key = `aggregateType:aggregateId`**; полный W3C Trace Context propagation; отдельный `outboxObjectMapper` без access-modifier'а |
| 4 | Cache-aside + автоинвалидация | Spring Cache + `RedisCacheManager` (без L1) | **Раздельные ObjectMapper'ы per-namespace (G2):** `outboxObjectMapper` для `aggregate`/`dto`/`page`/`mapping-plan`, `accessAwareObjectMapper` — только `projection`. Reverse-индексы (`page-deps`/`page-deps-by-type`/`ref-deps`) с MULTI/EXEC; **async-default** инвалидация; **никаких SCAN** на горячем пути |
| 5 | 1 агрегат-root = 1 `@Entity` | `@MappedSuperclass`, `@Embedded`, `@ElementCollection`, внутренние `@Entity` без `@TypeId` | `AbstractAggregate<ID>` + `AggregateIdGenerator`; **flat field tree** с recursive `@FieldId` в embedded; внутренние entities не имеют отдельного aggregate-репозитория; **`@ElementCollection` field-level access описан явно (G10)** |
| 6 | Репозитории + TypeId + AccessFiltered | Spring Data JPA + Hibernate `@Filter`/`@FilterDef` | `RepositoryRegistry`, `@TypeId` на интерфейсе, `@AccessFiltered(filterField=…, bypassPolicy=AUTO_TRANSITIVE)`; **annotation-based AOP**; `@PostLoadAccessCheck` **подразумевается** для filtered-типов; `PostLoadMode` = `THROW`/`MARK_AND_DROP` (G4); **fail-closed default**; `@AggregateLockingPolicy` (incl. OPTIMISTIC_FORCE_INCREMENT); типизация фильтр-параметров из `referencedTypeId` |
| 7 | Ссылки агрегатов + Jackson | `@JsonComponent`, `BeanSerializerModifier`; кастомный Hibernate `CompositeUserType` | `AggregateReference<T,ID>` хранится в **типизированных колонках** через `AggregateReferenceXxxUserType`-наследников (G3 — корректные сигнатуры Hibernate 6); `RefBatchPrefetcher` с **filter-aware** prefetch и `@PrefetchDepth`; стек path'ов в `AccessAwareEmbeddableWriter` (включая `@ElementCollection`); per-ref access-check |
| 8 | Доменные события | `@EntityListeners`, `TransactionSynchronization` | `onPreFlush` / `onBeforeCommit` / `onAfterCommit`, `@DomainCallback` + `CallbackDispatcher` (хранит `beanName + Method`, резолвит из контекста); in-flush guard через `ThreadLocal` |
| 9 | AccessMetric | `@Embeddable` + JSON-колонка (Hibernate 6 `@JdbcTypeCode(JSON)`) | **Полностью иммутабельный**, **с `equals`/`hashCode`**: `AccessMetricPayload` (record); `withRole()`/`withGlobalFlags()` возвращают новый экземпляр; флаговая шкала `AccessFlags` |
| 10 | «Двойник» доступа | Jackson `BeanSerializerModifier` + `AccessProjectionAdvice` | `AccessProjectionTemplate` — план без values; cache хранит baseline-вид (null для baseline-hidden); per-instance cleanup в `wrapPage`; **fingerprint включает instanceId+principalId, если у пользователя есть instance-ACL grant для типа (G9)**, иначе только effective-bits плана |
| 11 | Запекание на старте | Один `SmartInitializingSingleton` (`MetadataBootstrapper`), ClassGraph | `MetadataSnapshot` (immutable), публикация через DI-`MetadataSnapshotProvider`; ordering через `@Order(HIGHEST_PRECEDENCE+1000)` для зависящих компонентов; `staticGet()` возвращает `Optional`; **2-stage migration: schema-only Liquibase → `DataSeedRunner` после bootstrap (G7)** |
| 12 | Object + Pageable views | Spring Data `Page`, `Specification` | `AccessAwarePage<T>` envelope; **`FilteredCountQuery` через `CriteriaBuilder` (G5)** — `additionalSpec` учитывается; `AccessAwareReportingService` для агрегаций (G10) |
| 13 | DTO-маппинг (параллельный JSON-каналу) | **MapStruct 1.6+** | `AccessAwareMapper<A,D>`, `AggregateReferenceMapper`; `MappingContext` через MapStruct `@Context`; `applyInbound` **бросает** при запрещённой записи; deep-snapshot через JSON round-trip; `AccessAwareAfterMapping` как `@Component` |
| 14 | Admin/Root привилегии | — (полностью кастом) | `AccessFlags.ADMIN_READ/ADMIN_WRITE/ROOT_READ/ROOT_WRITE` + `expand()`; `AccessFilterActivator` уважает admin/root + автотранзитивный bypass через `AccessFilterGraph`; `GrantService` под `@PreAuthorize('GRANT_ADMIN_ROOT')` с обязательным audit log; `SystemAuthentication` имеет реальные authorities |
| **+** | **Авторство и аудит** | Spring Data Auditing | `AuditorAware<AggregateReference<UserAggregate,?>>`; `AuditFacade` как единственный шлюз к Envers; `PrincipalRevisionListener` записывает SYSTEM-ref для системных операций |
| **+** | **Контекст-пропагация** | Micrometer **ContextSnapshot API** | Прозрачно для `@Async`, virtual threads, Reactor |
| **+** | **Soft-delete** | Hibernate 6.4+ native `@SoftDelete` | ROOT_READ обходит видимость удалённых через `SoftDeleteAwareReader` |
| **+** | **Healthcheck** | Spring Boot Actuator | `MetadataHealthIndicator` блокирует трафик до завершения bootstrap'а |

---

## 1. Архитектурные принципы

1. **Метаданные доступа компилируются один раз на старте.** Все `@FieldId`, `@TypeId`, `@AccessFiltered`, граф зависимостей и шаблоны `_access`-проекций строятся в **единственном** `SmartInitializingSingleton.afterSingletonsInstantiated()` (`MetadataBootstrapper`) и публикуются как неизменяемый `MetadataSnapshot` через `MetadataSnapshotProvider`. Рантайм — только lookup. Зависящие компоненты (`MapperRegistry`, `AccessFilterActivator`, `CallbackDispatcher`) реализуют `SmartInitializingSingleton` с `@Order(HIGHEST_PRECEDENCE + 1000)` — Spring гарантирует порядок инициализации без хрупких `@DependsOn(<string>)`.

2. **Deny-by-default.** Поле без `@FieldId` — невидимо. Репозиторий без `@TypeId` — не регистрируется (fail-fast). Ссылка `AggregateReference` без `@ValidAggregateRef` — fail-fast. Любой `@Embeddable`, у которого хоть одно `persistent`-поле без `@FieldId`, — fail-fast. `@PostLoadAccessCheck` подразумевается для всех `@AccessFiltered`-агрегатов.

3. **Три независимых уровня контроля:**
   - **Row-level** — Hibernate `@Filter`, активируется AOP-перехватчиком на **read-методах** репозитория через **annotation-based pointcut** (маркер `@AggregateRepository`); **fail-closed по умолчанию** для `@AccessFiltered`-агрегатов при отсутствии `AccessContext`. Подразумеваемая защита `@PostLoadAccessCheck` для `findById`-leak'а.
   - **Field-level** — Jackson `BeanSerializerModifier` (read), MapStruct `AccessAwareMappingHelper` (read+write через `@BeforeMapping` snapshot + `@AfterMapping` reconciliation), Hibernate `PreUpdateEventListener`/`PreInsertEventListener` (write).
   - **Type-level** — `AccessResolver` + `MetadataSnapshot.globalGrants()`.

4. **Никаких ThreadLocal'ов в маппинге.** `MappingContext` передаётся явно через MapStruct `@Context` параметр — совместимо с virtual threads, не требует cleanup'а.

5. **`AccessContext` — обязателен для всех write-путей.** Любая попытка `repo.save` без bound `AccessContext` для `@AccessChecked(strict=true)`-агрегата (default) — `AccessDeniedException`. Миграции и batch'и обязаны явно вызвать `holder.bind(SystemAccessContexts.maxPrivileges())`.

6. **Никаких мутаций managed-сущностей** в обработчиках доступа. Field-hiding только в слоях сериализации/маппинга. `@PostLoadAccessCheck` **только бросает**, не модифицирует. `MapStruct.applyInbound` **бросает** при попытке записи запрещённого поля.

7. **Один маршрут публикации события — outbox.** Доменные события для внешних потребителей идут **только** через outbox → Kafka. Axon используется как **внутренний saga-state-store**, его `EventBus` не публикует ничего наружу. Saga-этапы общаются с миром через тот же outbox. Идентификация события — через **`@DomainEvent.stableName`**, не через FQN класса.

8. **Cache имеет одну точку правды — Redis.** Никаких L1 (Caffeine для агрегатных кешей). Распределённая инвалидация — асинхронно для всех нод. Reverse-индексы (`page-deps-by-type`, `ref-deps`) поддерживают точечную инвалидацию **без SCAN**.

9. **Все типы ID — типизированные колонки.** `AggregateReference` хранится не в `VARCHAR`, а в native колонках через `AggregateReferenceUserType`-наследников. FK enforcement и индексирование работают штатно.

10. **Глобальная уникальность `@FieldId`** — рекурсивно, включая embedded. `AggregateDescriptor.fields()` хранит **flat-tree** всех полей с `path`'ом от корня агрегата.

11. **Single-source-of-truth для системного principal'а.** `SYSTEM_PRINCIPAL_ID = "__SYSTEM__"` — единая константа. `SystemAccessContexts` отдаёт singleton-cached контексты для read-only / read-write / max-privileges / read-only-for-type сценариев. `SystemAuthentication` имеет реальные authorities, под ними работают `@PreAuthorize`.

12. **Bean Validation enforcement через интеграционные тесты, не ArchUnit.** ArchUnit не выполняет data-flow analysis. Контракт «`Validator.validate` вызывается перед `repo.save`» проверяется `@SpringBootTest`-сценариями с SpyBean'ом.

13. **Hibernate event-listener'ы регистрируются через `IntegratorProvider`**, передаваемый Spring Boot'ом по свойству `hibernate.integrator_provider` (см. §15.2). Listener'ы существуют к моменту первого SQL-запроса; окно «без access-checks» отсутствует. Контракт регистрации проверяется интеграционным тестом `AccessListenerRegistrationIT`.

14. **Подразумеваемые умолчания, явный opt-out.** `@PostLoadAccessCheck` подразумевается для filtered-типов; `@DomainEvent` обязан иметь `stableName`; `@FieldId.defaultAccess = HIDDEN` если не указано.

---

## 2. Структура проекта (multi-module)

```
project/
├── core-ddd/                # AbstractAggregate, AggregateReference, AccessLevel/Flags, аннотации, DefaultAccess, UserClaim, @DomainEvent
│                            # Зависимости: jakarta.persistence-api, jakarta.validation-api
├── core-security/           # AccessFlags, AccessLevel, AccessMetric, AccessResolver, AccessContext, SystemAccessContexts, GlobalGrants
├── core-persistence/        # AbstractAggregateRepository, RepositoryRegistry, AccessFilterActivator, AggregateReferenceUserType, AccessAwareHibernateConfig
├── core-eventing/           # OutboxPoller, OutboxWriter, CallbackDispatcher, DomainEventRegistry, Spring Cloud Stream consumers, KafkaSagaBridge
├── core-workflow/           # Temporal-обёртки; Axon (только saga-state-store); IdempotencyGuard
├── core-cache/              # RedisCacheManager, KeyGenerators, AccessProjectionCache, PageCacheWriter
├── core-web/                # AccessProjection-сериализация, AccessContextFilter, AccessProjectionAdvice, BatchPrefetchingResponseBodyAdvice, MetadataHealthIndicator
├── core-audit/              # AuditFacade — единственный шлюз к Envers AuditReader
├── domain-customer/         # CustomerAggregate, CustomerRepository, CustomerService, CustomerMapper
├── domain-order/            # ...
└── app-bootstrap/           # @SpringBootApplication, @EnableJpaRepositories, конфиги
```

`core-*` — без зависимости от Spring Boot (только `spring-context`, `spring-data-jpa`, `jakarta.*`, `io.micrometer:context-propagation`).

**ArchUnit гарантии:**
- `domain-*` не зависит от других `domain-*`.
- `core-ddd` не зависит от `core-persistence`, `core-cache`, `core-web`.
- `@Entity` существует только внутри `domain-*`.
- Никакой код в `core-web`/`core-cache`/`domain-*` не использует статические методы доступа к метаданным — всё через DI.
- JPQL `JOIN FETCH` на `@AccessFiltered`-агрегаты запрещён без явного `@FilterJoinTable` (детектируется парсингом JPQL).
- Liquibase/Flyway change-классы обязаны оборачивать `execute(...)` в `try (var s = holder.bind(SystemAccessContexts.maxPrivileges())) { ... }`.
- **`AuditReader` и `AuditQuery` — доступны только из `core-audit`**. Прямой доступ из других модулей запрещён.
- **`StreamBridge.send` запрещён вне `core-eventing`.** Внешние события публикуются только через outbox.
- **`Axon.EventGateway` запрещён вне saga-классов и `core-workflow`.** Внутренние saga-events не утекают наружу.
- `@AggregateRepository`-маркер проставлен на каждый `AggregateRepository`-наследник.
- `Class.forName(...)` запрещён в `core-eventing` (через `DomainEventRegistry`).

---

## 3. Технологический стек

```gradle
ext {
    springBootVersion        = '3.4.8'
    springCloudVersion       = '2024.0.1'      // КРИТИЧНО: SC 2024.x для SB 3.4.x
    axonVersion              = '4.10.3'
    temporalVersion          = '1.27.0'
    hypersistenceVersion     = '3.8.2'
    micrometerContextVersion = '1.1.1'
    mapstructVersion         = '1.6.3'
}

dependencies {
    // --- Spring Boot
    implementation 'org.springframework.boot:spring-boot-starter-web'
    implementation 'org.springframework.boot:spring-boot-starter-validation'
    implementation 'org.springframework.boot:spring-boot-starter-data-jpa'
    implementation 'org.springframework.boot:spring-boot-starter-data-redis'
    implementation 'org.springframework.boot:spring-boot-starter-cache'
    implementation 'org.springframework.boot:spring-boot-starter-security'
    implementation 'org.springframework.boot:spring-boot-starter-aop'
    implementation 'org.springframework.boot:spring-boot-starter-actuator'

    // --- Messaging
    implementation 'org.springframework.cloud:spring-cloud-starter-stream-kafka'

    // --- Saga state-store (без EventBus наружу!)
    implementation "org.axonframework:axon-spring-boot-starter:${axonVersion}"

    // --- Workflow
    implementation "io.temporal:temporal-spring-boot-starter-alpha:${temporalVersion}"

    // --- Context propagation (CRITICAL для @Async/VT/Reactor)
    implementation "io.micrometer:context-propagation:${micrometerContextVersion}"

    // --- JSON-колонки + ID
    implementation "io.hypersistence:hypersistence-utils-hibernate-63:${hypersistenceVersion}"
    implementation 'io.hypersistence:hypersistence-tsid:2.1.3'
    implementation 'com.fasterxml.uuid:java-uuid-generator:5.1.0'   // UUID v7

    // --- Сканирование классов на старте
    implementation 'io.github.classgraph:classgraph:4.8.179'

    // --- DTO / MapStruct
    implementation "org.mapstruct:mapstruct:${mapstructVersion}"
    annotationProcessor "org.mapstruct:mapstruct-processor:${mapstructVersion}"
    annotationProcessor 'org.projectlombok:lombok-mapstruct-binding:0.2.0'

    // --- Аудит и наблюдаемость
    implementation 'org.hibernate.orm:hibernate-envers'
    implementation 'io.micrometer:micrometer-registry-prometheus'
    implementation 'io.micrometer:micrometer-tracing-bridge-otel'

    // --- Тесты
    testImplementation 'org.springframework.boot:spring-boot-starter-test'
    testImplementation 'org.testcontainers:postgresql'
    testImplementation 'org.testcontainers:kafka'
    testImplementation 'org.testcontainers:redis'
    testImplementation 'com.tngtech.archunit:archunit-junit5:1.3.0'
}
```

### 3.1. Production application.yml

```yaml
spring:
  threads:
    virtual:
      enabled: true                     # Java 21 virtual threads для tomcat/scheduling

  datasource:
    url: jdbc:postgresql://...
    hikari:
      maximum-pool-size: 20
      minimum-idle: 5
      connection-timeout: 3000
      idle-timeout: 600000
      max-lifetime: 1800000
      leak-detection-threshold: 30000   # CRITICAL: ловим утечки connection'ов
      data-source-properties:
        cachePrepStmts: true
        prepStmtCacheSize: 500
        prepStmtCacheSqlLimit: 2048
        useServerPrepStmts: true
        rewriteBatchedStatements: true

  jpa:
    open-in-view: false                                # CRITICAL: фильтры не «утекут» в JSON-сериализацию
    properties:
      hibernate.jdbc.batch_size: 50
      hibernate.order_inserts: true
      hibernate.order_updates: true
      hibernate.cache.use_second_level_cache: false   # CRITICAL: L2 не уважает @Filter
      jakarta.persistence.validation.mode: none       # CRITICAL: Bean Validation ВЫЗЫВАЕТСЯ ЯВНО

app:
  cache:
    schema-version: v1                  # bump при breaking-изменениях формата кеша

  ddd:
    bootstrap:
      scan-packages:
        - com.example
    access:
      strict-default: true              # default для @AccessChecked
      implicit-post-load-check: true    # подразумеваемая @PostLoadAccessCheck для @AccessFiltered
    refprefetch:
      default-depth: 3                  # дефолт для @PrefetchDepth

  outbox:
    poll-interval-ms: 1000
    batch-size: 50
    max-attempts: 16
```

### 3.2. Тестовый профиль

```yaml
# application-test.yml
spring:
  jpa:
    properties:
      jakarta.persistence.validation.mode: callback   # тесты save() напрямую — нужна валидация
```

### 3.3. Bean Validation enforcement — через интеграционные тесты

> **Принципиально:** ArchUnit **не выполняет** data-flow analysis. Контракт «`Validator.validate` вызывается перед `repo.save`» **не enforce'ится** статически. Вместо этого — **обязательный** интеграционный тест-pattern для каждого сервиса:

```java
@SpringBootTest
class CustomerServiceValidationContractTest {

    @SpyBean Validator validator;
    @Autowired CustomerService service;
    @SpyBean CustomerRepository repo;

    @Test
    void update_invokesValidatorBeforeSave() {
        var dto = new CustomerUpdateDto(/*...*/);
        service.updateFromDto(UUID.randomUUID(), dto);

        InOrder order = inOrder(validator, repo);
        order.verify(validator).validate(any(CustomerAggregate.class));
        order.verify(repo).save(any(CustomerAggregate.class));
    }
}
```

ArchUnit-правило (слабое, но реализуемое): любой Spring `@Service`, мутирующий `AbstractAggregate`-наследников, **должен инжектить `Validator`**:

```java
@ArchTest
static final ArchRule services_mutating_aggregates_must_inject_validator =
    classes().that().resideInAPackage("..service..")
             .and().areAnnotatedWith(Service.class)
             .should(haveDependencyOn(Validator.class).orNotMutateAggregates());
```

---
## 4. Модель доступа: флаги и уровни (требования 5, 9, 14)

### 4.1. `AccessFlags` — биты

Унифицированная флаговая шкала. Любая комбинация прав = OR битов, любая проверка = AND с маской.

```java
public final class AccessFlags {
    private AccessFlags() {}

    public static final int NONE         = 0;

    // --- Plain: подчиняются всем фильтрам и field-level правилам
    public static final int READ         = 1 << 0;   // 0x01
    public static final int WRITE        = 1 << 1;   // 0x02

    // --- Admin: обходят instance-level (Hibernate filters, ownAccess); НЕ обходят field-level
    public static final int ADMIN_READ   = 1 << 2;   // 0x04
    public static final int ADMIN_WRITE  = 1 << 3;   // 0x08

    // --- Root: дополнительно обходят field-level HIDDEN/WRITE_ONLY
    public static final int ROOT_READ    = 1 << 4;   // 0x10
    public static final int ROOT_WRITE   = 1 << 5;   // 0x20

    /**
     * Расширяет флаги по правилу импликации:
     *   ROOT_WRITE  ⇒ ROOT_READ | ADMIN_WRITE | ADMIN_READ | WRITE | READ
     *   ROOT_READ   ⇒ ADMIN_READ | READ
     *   ADMIN_WRITE ⇒ ADMIN_READ | WRITE | READ
     *   ADMIN_READ  ⇒ READ
     */
    public static int expand(int flags) {
        int r = flags;
        if ((r & ROOT_WRITE)  != 0) r |= ROOT_READ | ADMIN_WRITE | ADMIN_READ | WRITE | READ;
        if ((r & ROOT_READ)   != 0) r |= ADMIN_READ | READ;
        if ((r & ADMIN_WRITE) != 0) r |= ADMIN_READ | WRITE | READ;
        if ((r & ADMIN_READ)  != 0) r |= READ;
        return r;
    }

    public static boolean has(int flags, int required) {
        return (expand(flags) & required) == required;
    }
}
```

### 4.2. `WriteMode` — квалификатор записи

```java
public enum WriteMode {
    /** Запись разрешена всегда при наличии бита WRITE. Дефолт. */
    ANY,
    /** Init-once: запись разрешена ТОЛЬКО пока текущее значение пустое. */
    MODIFY_EMPTY,
    /** Write-only: чтение запрещено даже при READ (если нет ROOT_READ). */
    WRITE_ONLY;

    /** При пересечении прав берём наиболее СТРОГИЙ режим. */
    public WriteMode strictest(WriteMode other) {
        if (this == WRITE_ONLY    || other == WRITE_ONLY)    return WRITE_ONLY;
        if (this == MODIFY_EMPTY  || other == MODIFY_EMPTY)  return MODIFY_EMPTY;
        return ANY;
    }
}
```

### 4.3. `AccessLevel` — record с каноническим инвариантом

`AccessLevel.flags` ВСЕГДА хранится в expanded-форме. Compact-конструктор record'а нормализует значение через `AccessFlags.expand`. Это гарантирует, что:
- `equals` / `hashCode` / `toString` консистентны независимо от способа создания.
- `intersect` / `union` не накладывают expand повторно.
- Debug-инструменты видят одно и то же представление.

```java
public record AccessLevel(int flags, WriteMode mode) {

    /** Канонический инвариант: flags всегда нормализован. */
    public AccessLevel {
        flags = AccessFlags.expand(flags);
        if (mode == null) mode = WriteMode.ANY;
    }

    public static final AccessLevel HIDDEN     = new AccessLevel(AccessFlags.NONE,  WriteMode.ANY);
    public static final AccessLevel READ_ONLY  = new AccessLevel(AccessFlags.READ,  WriteMode.ANY);
    public static final AccessLevel WRITE_ONLY = new AccessLevel(AccessFlags.WRITE, WriteMode.WRITE_ONLY);
    public static final AccessLevel READ_WRITE =
            new AccessLevel(AccessFlags.READ | AccessFlags.WRITE, WriteMode.ANY);
    public static final AccessLevel INIT_ONCE  =
            new AccessLevel(AccessFlags.READ | AccessFlags.WRITE, WriteMode.MODIFY_EMPTY);

    public boolean canRead() {
        // flags уже expanded — ROOT_READ-bit гарантирован при наличии любого root/admin-бита через expand
        if ((flags & AccessFlags.ROOT_READ) != 0) return true;     // root_read обходит WRITE_ONLY/HIDDEN
        if (mode == WriteMode.WRITE_ONLY)         return false;
        return (flags & AccessFlags.READ) != 0;
    }

    public boolean canWrite(@Nullable Object currentValue) {
        if ((flags & AccessFlags.ROOT_WRITE) != 0) return true;    // root_write обходит всё
        if ((flags & AccessFlags.WRITE) == 0)      return false;
        if (mode == WriteMode.MODIFY_EMPTY)        return !isPresent(currentValue);
        return true;
    }

    public boolean bypassesInstanceFilters() {
        return (flags & (AccessFlags.ADMIN_READ | AccessFlags.ADMIN_WRITE
                       | AccessFlags.ROOT_READ  | AccessFlags.ROOT_WRITE)) != 0;
    }

    /** Пересечение прав: AND-маска флагов, наиболее строгий WriteMode. */
    public AccessLevel intersect(AccessLevel other) {
        // flags обоих уже expanded — никакого re-expand
        return new AccessLevel(this.flags & other.flags, this.mode.strictest(other.mode));
    }

    /** Объединение прав (union): OR-маска флагов; ANY-мода берётся, если хоть у одного ANY. */
    public AccessLevel union(AccessLevel other) {
        WriteMode m = (this.mode == WriteMode.ANY || other.mode == WriteMode.ANY)
                ? WriteMode.ANY : this.mode.strictest(other.mode);
        return new AccessLevel(this.flags | other.flags, m);
    }

    private static boolean isPresent(Object v) {
        if (v == null) return false;
        if (v instanceof CharSequence cs) return !cs.isEmpty();
        if (v instanceof Collection<?> c) return !c.isEmpty();
        if (v instanceof Map<?, ?> m)     return !m.isEmpty();
        return true;
    }
}
```

> **Изменение относительно v8:** в v8 `intersect`/`union` каждый раз вызывали `AccessFlags.expand` локально, и результат **смешивал** raw- и expanded-формы (зависело от вызывающего кода). В v9 expanded-инвариант обеспечивается на этапе конструирования record'а.

### 4.4. `DefaultAccess` — enum для аннотаций

Java JLS §9.6.1 запрещает `record` в значениях аннотаций. Используем `enum DefaultAccess` как «фасад» над `AccessLevel`-комбинациями:

```java
public enum DefaultAccess {
    HIDDEN     (AccessFlags.NONE,                          WriteMode.ANY),
    READ_ONLY  (AccessFlags.READ,                          WriteMode.ANY),
    WRITE_ONLY (AccessFlags.WRITE,                         WriteMode.WRITE_ONLY),
    READ_WRITE (AccessFlags.READ | AccessFlags.WRITE,      WriteMode.ANY),
    INIT_ONCE  (AccessFlags.READ | AccessFlags.WRITE,      WriteMode.MODIFY_EMPTY);

    public final int       flags;
    public final WriteMode mode;

    DefaultAccess(int f, WriteMode m) { this.flags = f; this.mode = m; }

    public AccessLevel toLevel() { return new AccessLevel(flags, mode); }
}
```

### 4.5. Семантическая таблица admin/root

| Сценарий | Plain | `ADMIN_READ` | `ADMIN_WRITE` | `ROOT_READ` | `ROOT_WRITE` |
|---|---|---|---|---|---|
| Hibernate filter в репо T | применяется | **bypass** | **bypass** | **bypass** | **bypass** |
| Hibernate filter в другом репо, фильтрующем по T | применяется | **bypass** | **bypass** | **bypass** | **bypass** |
| `ownAccess` (instance ACL) на чтение в T | применяется | **bypass** | **bypass** | **bypass** | **bypass** |
| `ownAccess` (instance ACL) на запись в T | применяется | применяется | **bypass** | применяется | **bypass** |
| `@FieldId.defaultAccess = HIDDEN` (чтение) | применяется | применяется | применяется | **bypass** | **bypass** |
| `@FieldId.defaultAccess = WRITE_ONLY` (чтение) | применяется | применяется | применяется | **bypass** | **bypass** |
| `WriteMode.MODIFY_EMPTY` (запись при непустом current) | применяется | применяется | применяется | применяется | **bypass** |
| Soft-delete видимость | применяется | применяется | применяется | **bypass** | **bypass** |

### 4.6. `AccessFiltered` поля и автотранзитивный bypass

```java
public enum BypassPolicy {
    /** Только bypass по filter.referencedTypeId(). Дефолт. */
    EXPLICIT_ONLY,
    /**
     * Транзитивно: если для referencedTypeId есть bypass — применяется;
     * если нет — проверяется и для всех типов, в которые referenced-тип ссылается
     * через AggregateReference-поля (рекурсивно по AccessFilterGraph).
     */
    AUTO_TRANSITIVE
}
```

Транзитивное замыкание строится **на старте** в `AccessFilterGraph` (см. §24) — при `AUTO_TRANSITIVE` фильтр запоминает не один `referencedTypeId`, а **множество** транзитивно достижимых typeIds.

---

## 5. AccessMetric — единая структура хранения (полностью иммутабельная)

### 5.1. Структура

`AccessMetric` упакован в **одну** JSON-колонку. Это атомарная единица обновления, индексируется одним GIN-индексом, не плодит миграции при добавлении новых видов доступа. Структура **полностью иммутабельна**: `with*`-методы возвращают **новый** экземпляр. Имеет корректные `equals`/`hashCode` через `payload` — критично для сравнений в `AccessAwarePreUpdateListener` и `applyInbound`.

```java
@Embeddable
public final class AccessMetric implements Serializable {

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "access_metric", columnDefinition = "jsonb")
    private AccessMetricPayload payload;

    /** JPA-конструктор. */
    protected AccessMetric() { this.payload = AccessMetricPayload.empty(); }

    public AccessMetric(AccessMetricPayload p) {
        this.payload = (p == null) ? AccessMetricPayload.empty() : p;
    }

    private AccessMetricPayload payloadOrEmpty() {
        return payload == null ? AccessMetricPayload.empty() : payload;
    }

    /** Hibernate-only setter для load из БД. */
    void setPayload(AccessMetricPayload p) { this.payload = p; }

    public AccessMetricPayload payload()            { return payloadOrEmpty(); }
    public int globalFlags()                        { return payloadOrEmpty().globalFlags(); }
    public Map<Long,Integer> typeFlags()            { return payloadOrEmpty().typeFlags(); }
    public Map<String,RoleEntry> roleFlags()        { return payloadOrEmpty().roleFlags(); }
    public Map<Long,Set<String>> instanceWriteAcl() { return payloadOrEmpty().instanceWriteAcl(); }

    // -------- ИММУТАБЕЛЬНЫЕ with-методы --------

    public AccessMetric withRole(String role, int flags, WriteMode mode) {
        return new AccessMetric(payloadOrEmpty().addRole(role, flags, mode));
    }

    public AccessMetric withTypeFlags(long typeId, int flags) {
        return new AccessMetric(payloadOrEmpty().addTypeFlags(typeId, flags));
    }

    public AccessMetric withGlobalFlags(int flags) {
        return new AccessMetric(payloadOrEmpty().addGlobalFlags(flags));
    }

    public AccessMetric withInstanceWhitelist(long typeId, String instanceId) {
        return new AccessMetric(payloadOrEmpty().addInstanceWhitelist(typeId, instanceId));
    }

    public static AccessMetric empty() { return new AccessMetric(AccessMetricPayload.empty()); }

    // -------- equals/hashCode (КРИТИЧНО для Objects.equals в access-listener'ах) --------

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AccessMetric a)) return false;
        return Objects.equals(this.payloadOrEmpty(), a.payloadOrEmpty());
    }

    @Override
    public int hashCode() { return payloadOrEmpty().hashCode(); }

    @Override
    public String toString() { return "AccessMetric{" + payloadOrEmpty() + "}"; }

    /** Иммутабельный payload — Jackson-сериализуемый record. */
    public record AccessMetricPayload(
        int globalFlags,
        Map<Long, Integer>     typeFlags,           // typeId → admin/root flags
        Map<String, RoleEntry> roleFlags,           // roleName → (plain flags, mode)
        Map<Long, Set<String>> instanceWriteAcl     // typeId → set of allowed instance ids
    ) {
        public AccessMetricPayload {
            typeFlags        = (typeFlags        == null) ? Map.of() : Map.copyOf(typeFlags);
            roleFlags        = (roleFlags        == null) ? Map.of() : Map.copyOf(roleFlags);
            instanceWriteAcl = (instanceWriteAcl == null) ? Map.of() : Map.copyOf(instanceWriteAcl);
        }

        public static AccessMetricPayload empty() {
            return new AccessMetricPayload(0, Map.of(), Map.of(), Map.of());
        }

        public AccessMetricPayload addRole(String role, int flags, WriteMode mode) {
            Map<String, RoleEntry> next = new HashMap<>(roleFlags);
            next.merge(role, new RoleEntry(flags, mode),
                (a, b) -> new RoleEntry(a.flags() | b.flags(), b.mode().strictest(a.mode())));
            return new AccessMetricPayload(globalFlags, typeFlags, Map.copyOf(next), instanceWriteAcl);
        }

        public AccessMetricPayload addTypeFlags(long typeId, int flags) {
            Map<Long, Integer> next = new HashMap<>(typeFlags);
            next.merge(typeId, flags, (a, b) -> a | b);
            return new AccessMetricPayload(globalFlags, Map.copyOf(next), roleFlags, instanceWriteAcl);
        }

        public AccessMetricPayload addGlobalFlags(int flags) {
            return new AccessMetricPayload(globalFlags | flags, typeFlags, roleFlags, instanceWriteAcl);
        }

        public AccessMetricPayload addInstanceWhitelist(long typeId, String instanceId) {
            Map<Long, Set<String>> next = new HashMap<>(instanceWriteAcl);
            next.merge(typeId, Set.of(instanceId),
                (a, b) -> { var u = new HashSet<>(a); u.addAll(b); return Set.copyOf(u); });
            return new AccessMetricPayload(globalFlags, typeFlags, roleFlags, Map.copyOf(next));
        }
    }

    public record RoleEntry(int flags, WriteMode mode) {}
}
```

### 5.2. Композиция эффективного уровня

```
effective(user, typeId, fieldId, instance) =
        defaultFromFieldId(typeId, fieldId)                     // base (из @FieldId)
   ∩    globalGrants.evaluate(typeId, fieldId, user)            // type-level
   ∩    instance.ownAccess.effectiveLevel(user)                 // instance ACL
   ∪    user.adminRootBypass(typeId)                            // admin/root слой
```

```java
@Component
@RequiredArgsConstructor
public class AccessResolver {

    private final MetadataSnapshotProvider snapshots;
    private final UserAccessProvider users;

    public AccessLevel resolve(long typeId, long fieldId,
                               @Nullable AbstractAggregate<?> instance,
                               AccessContext ctx) {
        var snap = snapshots.get();
        FieldDescriptor fd = snap.field(typeId, fieldId);
        AccessMetric um = users.metricFor(ctx);

        AccessLevel base   = fd.defaultAccess();
        AccessLevel global = snap.globalGrants().evaluate(typeId, fieldId, um);
        AccessLevel inst   = instance == null
                ? AccessLevel.READ_WRITE
                : instance.ownAccess().effectiveLevel(um);

        AccessLevel plain = base.intersect(global).intersect(inst);

        int adminRootFlags = um.globalFlags() | um.typeFlags().getOrDefault(typeId, 0);
        if (adminRootFlags == 0) return plain;
        return plain.union(new AccessLevel(adminRootFlags, WriteMode.ANY));
    }

    public AccessLevel resolveRepository(long typeId, AccessContext ctx) {
        AccessMetric um = users.metricFor(ctx);
        var snap = snapshots.get();
        AccessLevel typeDefault = snap.aggregate(typeId).defaultRepoAccess();

        AccessLevel rolesLvl = um.roleFlags().values().stream()
                .map(re -> new AccessLevel(re.flags(), re.mode()))
                .reduce(AccessLevel.HIDDEN, AccessLevel::union);

        int adminRootFlags = um.globalFlags() | um.typeFlags().getOrDefault(typeId, 0);
        return typeDefault.union(rolesLvl).union(new AccessLevel(adminRootFlags, WriteMode.ANY));
    }

    /** Публичный shortcut для bypass-проверок и hash'а. */
    public AccessMetric userMetricFor(AccessContext ctx) { return users.metricFor(ctx); }
}
```

### 5.3. `GlobalGrants` — type-level гранты

```java
/**
 * Type-level гранты (не-instance, не-role): «всем пользователям с ролью X разрешён READ
 * на тип T, поле F». Заполняется из application.yml при старте.
 */
public final class GlobalGrants {

    private final Map<Long, AccessLevel> typeDefaults;                     // typeId → AccessLevel
    private final Map<Long, Map<Long, AccessLevel>> fieldOverrides;        // typeId → fieldId → AccessLevel
    private final Map<String, RoleGrant> roleGrants;                       // roleName → RoleGrant

    public AccessLevel evaluate(long typeId, long fieldId, AccessMetric userMetric) {
        AccessLevel base = AccessLevel.READ_WRITE;   // default open

        var fieldMap = fieldOverrides.get(typeId);
        if (fieldMap != null) {
            AccessLevel f = fieldMap.get(fieldId);
            if (f != null) base = base.intersect(f);
        }
        AccessLevel typeLevel = typeDefaults.get(typeId);
        if (typeLevel != null) base = base.intersect(typeLevel);

        AccessLevel rolesUnion = userMetric.roleFlags().entrySet().stream()
            .map(e -> {
                RoleGrant rg = roleGrants.get(e.getKey());
                if (rg == null) return AccessLevel.HIDDEN;
                return rg.levelForType(typeId, fieldId)
                         .orElse(AccessLevel.HIDDEN);
            })
            .reduce(AccessLevel.HIDDEN, AccessLevel::union);

        return base.union(rolesUnion);
    }

    public record RoleGrant(
        Map<Long, AccessLevel> typeLevel,
        Map<Long, Map<Long, AccessLevel>> fieldLevel
    ) {
        public Optional<AccessLevel> levelForType(long typeId, long fieldId) {
            var fields = fieldLevel.get(typeId);
            if (fields != null && fields.containsKey(fieldId)) return Optional.of(fields.get(fieldId));
            if (typeLevel.containsKey(typeId)) return Optional.of(typeLevel.get(typeId));
            return Optional.empty();
        }
    }

    /** Фабрика из application.yml — `app.ddd.access.grants.*`. */
    public static GlobalGrants fromConfig(GrantsProperties props) { /* mapping yaml → record */ }
}
```

### 5.4. `UserAccessProvider` — кеш `AccessMetric` per-user

Критичный момент: `loadFromDb` обёрнут в **узкий** bootstrap-контекст `systemReadOnlyForType(userTypeId)`. Это разрывает потенциальный bootstrap-loop, когда первое же обращение пользователя триггерит `AccessFilterActivator` → `metricFor` → cache miss → `loadFromDb` → снова `findById` под фильтром → снова `metricFor`. Узкий bypass даёт `ROOT_READ` **только** для типа `UserAggregate`, не для всех агрегатов.

```java
public interface UserAccessProvider {
    AccessMetric metricFor(AccessContext ctx);
}

@Component
@RequiredArgsConstructor
public class CaffeineUserAccessProvider implements UserAccessProvider {

    private final UserRepository userRepo;
    private final RegistryAccess registry;
    private final AccessContextHolder holder;
    private final SystemAccessContexts systems;
    private final MetadataSnapshotProvider snapshots;

    /** L1 — Caffeine, размером 10K user'ов, TTL 5 минут.
     *  Инвалидация — по событию изменения UserAggregate.access (через CacheInvalidationListener). */
    private final Cache<String, AccessMetric> cache = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(Duration.ofMinutes(5))
            .build();

    @Override
    public AccessMetric metricFor(AccessContext ctx) {
        // Системные contexts — синтетический metric, не идём в БД
        if (ctx.isSystem()) {
            if (ctx.isSystemMaxPrivileged()) {
                return AccessMetric.empty().withGlobalFlags(AccessFlags.ROOT_WRITE);
            }
            if (ctx.auth() instanceof SystemAuthentication sa) {
                return sa.synthesizedMetric();
            }
        }
        var ref = ctx.principalRef();
        if (ref == null) return AccessMetric.empty();
        String cacheKey = ref.targetTypeId() + ":" + ref.targetIdRaw();
        return cache.get(cacheKey, k -> loadFromDb(ref));
    }

    /**
     * Узкий bootstrap-bypass: оборачиваем findById в systemReadOnlyForType(userTypeId).
     * AccessFilterActivator увидит ROOT_READ для userTypeId и пропустит фильтр; PostLoadAccessCheck
     * увидит bootstrap-флаг и не бросит. Bypass валиден ТОЛЬКО для типа UserAggregate.
     */
    @SuppressWarnings("unchecked")
    private AccessMetric loadFromDb(AggregateReference<? extends UserAggregate, ?> ref) {
        long userTypeId = ref.targetTypeId();
        try (var ignored = holder.bind(systems.systemReadOnlyForType(userTypeId))) {
            var idClass = (Class<? extends Serializable>) snapshots.get().idClassByTypeId(userTypeId);
            var user = userRepo.findById(IdCodec.decode(ref.targetIdRaw(), idClass)).orElse(null);
            if (user == null) return AccessMetric.empty();
            return user.getAccess();
        }
    }

    /** Инвалидация — вызывается из CacheInvalidationListener при AggregateChangedEvent UserAggregate. */
    public void evictUser(String typeId, String idRaw) {
        cache.invalidate(typeId + ":" + idRaw);
    }
}
```

### 5.5. Хранение

```sql
CREATE INDEX user_access_metric_gin
    ON user_aggregate USING GIN (access_metric jsonb_path_ops);
```

### 5.6. Раздача admin/root прав через `GrantService`

```java
@Service
@RequiredArgsConstructor
public class GrantService {

    private final UserRepository userRepo;
    private final AuditLogger auditLog;
    private final CaffeineUserAccessProvider userAccessCache;

    @PreAuthorize("hasAuthority('GRANT_ADMIN_ROOT')")
    @Transactional
    public void grantRole(UUID userId, String role, int flags, WriteMode mode,
                          String justification, AccessContext grantorCtx) {
        var user = userRepo.findByIdLocked(userId).orElseThrow();
        user.setAccess(user.getAccess().withRole(role, flags, mode));
        userRepo.save(user);
        userAccessCache.evictUser(String.valueOf(user.typeId()), userId.toString());
        auditLog.record(GrantEvent.ofRole(userId, role, flags, mode,
                                          grantorCtx.principalRef(), justification));
    }

    @PreAuthorize("hasAuthority('GRANT_ADMIN_ROOT')")
    @Transactional
    public void grantTypeFlags(UUID userId, long typeId, int flags,
                               String justification, AccessContext grantorCtx) {
        var user = userRepo.findByIdLocked(userId).orElseThrow();
        user.setAccess(user.getAccess().withTypeFlags(typeId, flags));
        userRepo.save(user);
        userAccessCache.evictUser(String.valueOf(user.typeId()), userId.toString());
        auditLog.record(GrantEvent.ofType(userId, typeId, flags,
                                           grantorCtx.principalRef(), justification));
    }

    @PreAuthorize("hasAuthority('GRANT_ADMIN_ROOT')")
    @Transactional
    public void grantGlobalFlags(UUID userId, int flags, String justification,
                                 AccessContext grantorCtx) {
        var user = userRepo.findByIdLocked(userId).orElseThrow();
        user.setAccess(user.getAccess().withGlobalFlags(flags));
        userRepo.save(user);
        userAccessCache.evictUser(String.valueOf(user.typeId()), userId.toString());
        auditLog.record(GrantEvent.ofGlobal(userId, flags,
                                            grantorCtx.principalRef(), justification));
    }
}
```

`findByIdLocked` (§10.5) с `@AggregateLockingPolicy(OPTIMISTIC_FORCE_INCREMENT)` гарантирует, что конкурентные гранты сериализуются.

---

## 6. AccessContext — обогащённый контекст пользователя

### 6.1. Зачем явный контекст

`SecurityContextHolder` через `ThreadLocal` не работает в `@Async`/scheduled/post-commit/virtual-threads/Reactor пулах. Нужна **явная** структура, которая:
- содержит `Authentication` пользователя,
- содержит `AggregateReference<? extends UserAggregate, ?> principalRef` — ссылку на агрегат-обладателя прав,
- передаётся через стандартный механизм `Micrometer ContextSnapshot` — прозрачно для Spring async-инфраструктуры,
- доступна в Jackson, MapStruct, в обработчиках Hibernate без статических singleton'ов.

### 6.2. Структура

```java
public record AccessContext(
    Authentication auth,
    AggregateReference<? extends UserAggregate, ?> principalRef,
    AccessResolver resolver,
    MetadataSnapshot snapshot,
    ProjectionDirection direction,
    String accessKeyHash       // прерасчитанный hash для cache-ключей (full-hash, см. §6.4)
) {

    /** Статический технический идентификатор системного principal'а. */
    public static final String SYSTEM_PRINCIPAL_ID = "__SYSTEM__";

    public AccessLevel resolve(long typeId, long fieldId, @Nullable AbstractAggregate<?> i) {
        return resolver.resolve(typeId, fieldId, i, this);
    }

    public AccessLevel resolveRepository(long typeId) {
        return resolver.resolveRepository(typeId, this);
    }

    public boolean isSystem() {
        return principalRef != null && SYSTEM_PRINCIPAL_ID.equals(principalRef.targetIdRaw());
    }

    /** True, если у системного principal'а максимальные права (global ROOT_WRITE). */
    public boolean isSystemMaxPrivileged() {
        if (!isSystem()) return false;
        return auth instanceof SystemAuthentication sa && sa.maxPrivileged();
    }

    /** True, если контекст специально выпущен под bootstrap-load конкретного типа (см. §7.4). */
    public boolean isBootstrapForType(long typeId) {
        return auth instanceof SystemAuthentication sa && sa.isBootstrapFor(typeId);
    }
}

public enum ProjectionDirection { OUTBOUND, INBOUND }
```

### 6.3. Создание контекста на входе запроса

```java
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 50)
@RequiredArgsConstructor
public class AccessContextFilter extends OncePerRequestFilter {

    private final PrincipalRefResolver principalResolver;
    private final AccessResolver accessResolver;
    private final MetadataSnapshotProvider snapshots;
    private final AccessKeyHasher hasher;
    private final AccessContextHolder holder;
    private final UserAccessProvider userAccess;

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp,
                                    FilterChain chain) throws ServletException, IOException {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            chain.doFilter(req, resp);
            return;
        }
        // Two-phase hash: сначала light-hash для lookup metric'а, затем full-hash включая metric-version
        String lightHash = hasher.lightHash(auth);
        var partialCtx = new AccessContext(auth, principalResolver.resolve(auth),
                                           accessResolver, snapshots.get(),
                                           ProjectionDirection.OUTBOUND, lightHash);
        AccessMetric metric = userAccess.metricFor(partialCtx);
        String fullHash = hasher.fullHash(auth, metric);

        var ctx = new AccessContext(
            auth,
            principalResolver.resolve(auth),
            accessResolver,
            snapshots.get(),
            ProjectionDirection.OUTBOUND,
            fullHash
        );
        try (var ignored = holder.bind(ctx)) {
            chain.doFilter(req, resp);
        }
    }
}
```

### 6.4. `AccessKeyHasher` — двухфазный детерминированный hash для cache-ключей

`accessKeyHash` участвует в ключах cache'а. Если из него исключить версию `AccessMetric`, то после `grantRole`/`grantTypeFlags` кеши не инвалидируются — пользователь будет видеть устаревшую проекцию вплоть до TTL. Решение — двухфазный hash:

1. **`lightHash(auth)`** — только поля JWT (sub, authorities, claims). Стабильный per-token. Используется для lookup `AccessMetric` (через `UserAccessProvider`, который его игнорирует и работает по `principalRef`).
2. **`fullHash(auth, metric)`** — JWT + `AccessMetricPayload.hashCode()`. Используется как основной `accessKeyHash` в `AccessContext`. Меняется при любом обновлении метрики пользователя.

```java
@Component
public class AccessKeyHasher {

    /** Light-hash — только JWT, без метрики. Стабильный. */
    public String lightHash(Authentication auth) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            updateJwtPart(md, auth);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Full-hash — JWT + AccessMetricPayload.hashCode(). Меняется при grant'ах. */
    public String fullHash(Authentication auth, AccessMetric metric) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            updateJwtPart(md, auth);
            md.update((byte) 2);
            md.update(Integer.toString(metric.payload().hashCode()).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void updateJwtPart(MessageDigest md, Authentication auth) {
        /*
         * Hash построен из:
         *   - sub (user-id) из JWT;
         *   - sorted set of authority-strings;
         *   - sorted set of org_ids/customer_ids/... (значимые claim'ы фильтрации).
         *
         * НЕ включает: exp, iat, jti — иначе каждый refresh ломает кеш.
         */
        String subject = extractSubject(auth);
        md.update(subject.getBytes(StandardCharsets.UTF_8));
        md.update((byte) 0);
        authoritiesSorted(auth).forEach(a -> { md.update(a.getBytes(StandardCharsets.UTF_8)); md.update((byte) 0); });
        md.update((byte) 1);
        claimsSorted(auth).forEach(c -> { md.update(c.getBytes(StandardCharsets.UTF_8)); md.update((byte) 0); });
    }

    private String extractSubject(Authentication auth) {
        if (auth.getPrincipal() instanceof Jwt jwt) return jwt.getSubject();
        return String.valueOf(auth.getPrincipal());
    }

    private List<String> authoritiesSorted(Authentication auth) {
        return auth.getAuthorities().stream().map(GrantedAuthority::getAuthority).sorted().toList();
    }

    private List<String> claimsSorted(Authentication auth) {
        if (!(auth.getPrincipal() instanceof Jwt jwt)) return List.of();
        List<String> out = new ArrayList<>();
        for (UserClaim c : UserClaim.values()) {
            Object v = jwt.getClaim(c.jwtName);
            if (v instanceof Collection<?> col) {
                col.stream().map(String::valueOf).sorted().forEach(s -> out.add(c.name() + "=" + s));
            } else if (v != null) {
                out.add(c.name() + "=" + v);
            }
        }
        return out;
    }
}
```

`AccessMetricPayload.hashCode()` — generated record-level hashCode, стабилен для одного содержимого. При `grantRole`/`grantTypeFlags` payload меняется → hashCode меняется → fullHash меняется → кеш-ключи разные.

### 6.5. `PrincipalRefResolver`

```java
public interface PrincipalRefResolver {
    AggregateReference<? extends UserAggregate, ?> resolve(Authentication auth);
}

@Component
@RequiredArgsConstructor
public class JwtPrincipalRefResolver implements PrincipalRefResolver {

    private final AggregateReferenceFactory refFactory;

    @Override
    public AggregateReference<UserAggregate, UUID> resolve(Authentication auth) {
        UUID userId = UUID.fromString(((Jwt) auth.getPrincipal()).getSubject());
        return refFactory.of(UserAggregate.class, userId);
    }
}
```

### 6.6. `AccessContextHolder` + Micrometer ContextSnapshot

```java
@Component
public class AccessContextHolder {

    private static final ThreadLocal<AccessContext> CTX = new ThreadLocal<>();
    public static final String CONTEXT_KEY = "ddd.access.context";

    public Optional<AccessContext> tryGet() { return Optional.ofNullable(CTX.get()); }

    public AccessContext getOrThrow() {
        var c = CTX.get();
        if (c == null) throw new IllegalStateException(
            "AccessContext not bound on this thread. " +
            "Ensure ContextSnapshot.captureAll() is used for off-thread propagation, " +
            "or wrap the call in holder.bind(SystemAccessContexts.X(...)).");
        return c;
    }

    public Closeable bind(AccessContext ctx) {
        AccessContext prev = CTX.get();
        CTX.set(ctx);
        return () -> { if (prev == null) CTX.remove(); else CTX.set(prev); };
    }

    public void clear() { CTX.remove(); }
    AccessContext rawGet()              { return CTX.get(); }
    void rawSet(AccessContext c)        { CTX.set(c); }
}

@Configuration
public class ContextPropagationConfig {

    @Bean
    public ContextRegistry contextRegistry(AccessContextHolder holder) {
        ContextRegistry registry = ContextRegistry.getInstance();
        registry.registerThreadLocalAccessor(new ThreadLocalAccessor<AccessContext>() {
            @Override public Object key()                     { return AccessContextHolder.CONTEXT_KEY; }
            @Override public AccessContext getValue()         { return holder.rawGet(); }
            @Override public void setValue(AccessContext v)   { holder.rawSet(v); }
            @Override public void setValue()                  { holder.clear(); }
        });
        return registry;
    }

    @Bean
    public TaskDecorator contextPropagatingTaskDecorator() {
        return runnable -> {
            ContextSnapshot snapshot = ContextSnapshotFactory.builder().build().captureAll();
            return () -> { try (var scope = snapshot.setThreadLocals()) { runnable.run(); } };
        };
    }

    @Bean
    public ThreadPoolTaskExecutor applicationTaskExecutor(TaskDecorator decorator) {
        var ex = new ThreadPoolTaskExecutor();
        ex.setTaskDecorator(decorator);
        ex.initialize();
        return ex;
    }
}
```

### 6.7. Cross-tenant batch через `TransactionTemplate`

Hibernate L1 cache не учитывает access-контекст. В batch-задаче, обрабатывающей данные нескольких пользователей, нужно:
1. Каждый тенант — отдельная **управляемая** транзакция (через `PlatformTransactionManager`/`TransactionTemplate`), чтобы `@TransactionalEventListener` срабатывал и кеш-инвалидация работала.
2. После commit'а L1 пуст автоматически (managed-сущности detached'нуты commit'ом).
3. `em.clear()` между тенантами в рамках одной TX **не нужен** при правильном TX-management'е.

```java
@Service
@RequiredArgsConstructor
public class CrossTenantBatchService {

    private final PlatformTransactionManager txManager;
    private final AccessContextHolder holder;
    private final TenantProcessor processor;

    public void processForEachTenant(List<TenantContext> tenants) {
        var tt = new TransactionTemplate(txManager);
        tt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        for (TenantContext t : tenants) {
            tt.executeWithoutResult(status -> {
                try (var ignored = holder.bind(t.accessContext())) {
                    processor.processOne(t);
                }
                // commit по выходу из лямбды; managed-entities detach'нутся commit'ом
                // @TransactionalEventListener(AFTER_COMMIT) корректно сработает для cache-invalidation
            });
        }
    }
}
```

Ключевое отличие от анти-паттерна `EntityManagerFactory.createEntityManager()`: `TransactionTemplate` использует **существующий** `PlatformTransactionManager`, а значит работают:
- `@TransactionalEventListener` post-commit hooks,
- `TransactionSynchronizationManager.registerSynchronization` в `AggregateLifecycleListener`,
- Spring `@Async`-context propagation между тенантами.

---
## 7. SystemAccessContexts — системные principal'ы

### 7.1. Singleton-cached контексты

Системный principal — это `AggregateReference<UserAggregate>` с **статическим** `targetIdRaw = SYSTEM_PRINCIPAL_ID = "__SYSTEM__"`. Существует **четыре** варианта системного контекста, кешируемые в `@PostConstruct`:

```java
@Component
@RequiredArgsConstructor
public class SystemAccessContexts {

    private final MetadataSnapshotProvider snapshots;
    private final AccessResolver resolver;
    private final AccessKeyHasher hasher;
    private final AggregateReferenceFactory refs;

    private AccessContext readOnlyCached;
    private AccessContext readWriteCached;
    private AccessContext maxPrivilegesCached;
    private final ConcurrentHashMap<Long, AccessContext> readOnlyForTypeCache = new ConcurrentHashMap<>();

    @PostConstruct
    void init() {
        readOnlyCached      = build(SystemAuthentication.readOnly());
        readWriteCached     = build(SystemAuthentication.readWrite());
        maxPrivilegesCached = build(SystemAuthentication.maxPrivileged());
    }

    /** Read-only системный контекст. */
    public AccessContext systemReadOnly() { return readOnlyCached; }

    /** Системный read-write. */
    public AccessContext systemReadWrite() { return readWriteCached; }

    /**
     * МАКСИМАЛЬНЫЕ ПРАВА — global ROOT_WRITE. Использовать ТОЛЬКО для:
     *   • Liquibase/Flyway-runner'ов;
     *   • восстановительных batch'ей под audit-control'ем;
     *   • тестовых фикстур (test-profile).
     *
     * Каждый вызов на production должен быть оправдан явным комментарием/audit-record'ом.
     */
    public AccessContext maxPrivileges() { return maxPrivilegesCached; }

    /**
     * Узкий bypass: ROOT_READ ТОЛЬКО для конкретного typeId. Используется bootstrap-кодом
     * для разрыва циклов (см. §5.4, §7.4). НЕ выдаёт глобальных прав на чтение всех типов —
     * минимальный blast radius.
     */
    public AccessContext systemReadOnlyForType(long typeId) {
        return readOnlyForTypeCache.computeIfAbsent(typeId,
            tid -> build(SystemAuthentication.readOnlyFor(tid)));
    }

    private AccessContext build(SystemAuthentication auth) {
        var snap = snapshots.get();
        @SuppressWarnings("unchecked")
        var ref = (AggregateReference<? extends UserAggregate, ?>)
                  refs.system(UserAggregate.class, AccessContext.SYSTEM_PRINCIPAL_ID);
        // Для системного контекста full-hash = light-hash (нет per-user metric'а)
        return new AccessContext(auth, ref, resolver, snap,
                                 ProjectionDirection.OUTBOUND, hasher.lightHash(auth));
    }
}
```

### 7.2. `SystemAuthentication`

Системный `Authentication` имеет реальный набор `GrantedAuthority` в зависимости от `Mode`. Это критично для `@PreAuthorize`-методов: `GrantService.grantRole` под `@PreAuthorize("hasAuthority('GRANT_ADMIN_ROOT')")` теперь корректно работает, когда вызывается из миграционного скрипта под `maxPrivileges()`.

```java
public final class SystemAuthentication implements Authentication {

    public enum Mode { READ_ONLY, READ_WRITE, MAX_PRIVILEGED, READ_ONLY_FOR_TYPE }

    private static final List<GrantedAuthority> READ_ONLY_AUTH = List.of(
        new SimpleGrantedAuthority("ROLE_SYSTEM"),
        new SimpleGrantedAuthority("ROLE_SYSTEM_READ"));

    private static final List<GrantedAuthority> READ_WRITE_AUTH = List.of(
        new SimpleGrantedAuthority("ROLE_SYSTEM"),
        new SimpleGrantedAuthority("ROLE_SYSTEM_READ"),
        new SimpleGrantedAuthority("ROLE_SYSTEM_WRITE"));

    private static final List<GrantedAuthority> MAX_PRIVILEGED_AUTH = List.of(
        new SimpleGrantedAuthority("ROLE_SYSTEM"),
        new SimpleGrantedAuthority("ROLE_SYSTEM_READ"),
        new SimpleGrantedAuthority("ROLE_SYSTEM_WRITE"),
        new SimpleGrantedAuthority("ROLE_MIGRATION"),
        new SimpleGrantedAuthority("GRANT_ADMIN_ROOT"));

    private static final List<GrantedAuthority> READ_ONLY_FOR_TYPE_AUTH = List.of(
        new SimpleGrantedAuthority("ROLE_SYSTEM_BOOTSTRAP"));

    private final Mode mode;
    private final long typeIdRestriction;     // -1 для не-restricted; для READ_ONLY_FOR_TYPE — конкретный typeId

    private SystemAuthentication(Mode mode, long typeIdRestriction) {
        this.mode = mode;
        this.typeIdRestriction = typeIdRestriction;
    }

    public static SystemAuthentication readOnly()       { return new SystemAuthentication(Mode.READ_ONLY,        -1); }
    public static SystemAuthentication readWrite()      { return new SystemAuthentication(Mode.READ_WRITE,       -1); }
    public static SystemAuthentication maxPrivileged()  { return new SystemAuthentication(Mode.MAX_PRIVILEGED,   -1); }
    public static SystemAuthentication readOnlyFor(long typeId) {
        return new SystemAuthentication(Mode.READ_ONLY_FOR_TYPE, typeId);
    }

    public boolean maxPrivileged()              { return mode == Mode.MAX_PRIVILEGED; }
    public boolean isBootstrapFor(long typeId)  { return mode == Mode.READ_ONLY_FOR_TYPE && typeIdRestriction == typeId; }

    /** Синтезирует AccessMetric для UserAccessProvider. */
    public AccessMetric synthesizedMetric() {
        return switch (mode) {
            case READ_ONLY              -> AccessMetric.empty().withGlobalFlags(AccessFlags.READ);
            case READ_WRITE             -> AccessMetric.empty().withGlobalFlags(AccessFlags.READ | AccessFlags.WRITE);
            case MAX_PRIVILEGED         -> AccessMetric.empty().withGlobalFlags(AccessFlags.ROOT_WRITE);
            case READ_ONLY_FOR_TYPE     -> AccessMetric.empty().withTypeFlags(typeIdRestriction, AccessFlags.ROOT_READ);
        };
    }

    @Override public String getName() { return AccessContext.SYSTEM_PRINCIPAL_ID; }
    @Override public Object getCredentials() { return null; }
    @Override public Object getDetails() { return null; }
    @Override public Object getPrincipal() { return AccessContext.SYSTEM_PRINCIPAL_ID; }
    @Override public boolean isAuthenticated() { return true; }
    @Override public Collection<? extends GrantedAuthority> getAuthorities() {
        return switch (mode) {
            case READ_ONLY              -> READ_ONLY_AUTH;
            case READ_WRITE             -> READ_WRITE_AUTH;
            case MAX_PRIVILEGED         -> MAX_PRIVILEGED_AUTH;
            case READ_ONLY_FOR_TYPE     -> READ_ONLY_FOR_TYPE_AUTH;
        };
    }
    @Override public void setAuthenticated(boolean authenticated) {}
}
```

### 7.3. `ReconstructedAuthentication` — для Axon-handler'ов

Когда Axon-handler восстанавливает `AccessContext` из `MetaData` события (см. §18.3), `Authentication` пересоздаётся из загруженного `UserAggregate`:

```java
public final class ReconstructedAuthentication implements Authentication {

    private final UserAggregate user;
    private final List<GrantedAuthority> authorities;

    public ReconstructedAuthentication(UserAggregate user) {
        this.user = user;
        this.authorities = user.getAccess().roleFlags().keySet().stream()
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toList());
    }

    @Override public String getName() { return IdCodec.encode(user.getId()); }
    @Override public Object getCredentials() { return null; }
    @Override public Object getDetails() { return user; }
    @Override public Object getPrincipal() { return user; }
    @Override public boolean isAuthenticated() { return true; }
    @Override public Collection<? extends GrantedAuthority> getAuthorities() { return authorities; }
    @Override public void setAuthenticated(boolean authenticated) {}
}
```

### 7.4. Bootstrap-loop fix

Проблема: чтобы создать `AccessContext` для пользователя в Axon-handler'е, нужно загрузить `UserAggregate.findById(...)`. Но `findById` для `@AccessFiltered`-репозитория (или с `@PostLoadAccessCheck`) требует уже существующий `AccessContext`. Цикл.

**Решение:** для bootstrap-load `UserAggregate` в `AxonAccessContextRestoringInterceptor` (а в `CaffeineUserAccessProvider.loadFromDb` — встроенно, см. §5.4) — оборачивать вызов в **узкий** bypass-контекст `systemReadOnlyForType(userTypeId)`. После загрузки **берём метрику напрямую из объекта** и догреваем кеш `UserAccessProvider`'а — никакой двойной БД-загрузки:

```java
@Component
@RequiredArgsConstructor
public class UserAccessContextLoader {

    private final RegistryAccess registry;
    private final AccessResolver resolver;
    private final MetadataSnapshotProvider snapshots;
    private final AccessKeyHasher hasher;
    private final AggregateReferenceFactory refs;
    private final AccessContextHolder holder;
    private final SystemAccessContexts systems;
    private final CaffeineUserAccessProvider userAccessCache;

    @SuppressWarnings({"unchecked", "rawtypes"})
    public AccessContext loadFor(long principalTypeId, String principalIdRaw) {
        var snap = snapshots.get();

        UserAggregate user;
        try (var ignored = holder.bind(systems.systemReadOnlyForType(principalTypeId))) {
            // Внутри этого scope:
            //  - AccessFilterActivator пропускает фильтр (typeFlags имеет ROOT_READ для principalTypeId)
            //  - PostLoadAccessCheckListener видит bootstrap-флаг и не бросает
            AggregateRepository repo = registry.repositoryByTypeId(principalTypeId);
            var idClass = (Class<? extends Serializable>) snap.idClassByTypeId(principalTypeId);
            user = (UserAggregate) ((AggregateRepository) repo).findById(IdCodec.decode(principalIdRaw, idClass))
                    .orElseThrow(() -> new IllegalStateException(
                        "Principal " + principalTypeId + ":" + principalIdRaw + " not found"));
        }

        // КРИТИЧНО: метрику берём напрямую из загруженного агрегата.
        // Это устраняет двойную загрузку: вместо последующего userAccess.metricFor(partial)
        // → cache miss → loadFromDb (повторный findById) — мы догреваем кеш и используем сразу.
        AccessMetric metric = user.getAccess();
        userAccessCache.warmCache(principalTypeId, principalIdRaw, metric);

        var auth = new ReconstructedAuthentication(user);
        var ref  = (AggregateReference<? extends UserAggregate, ?>) refs.ofRaw(principalTypeId, principalIdRaw);

        return new AccessContext(auth, ref, resolver, snap,
                                 ProjectionDirection.OUTBOUND, hasher.fullHash(auth, metric));
    }
}
```

В `CaffeineUserAccessProvider` добавляется публичный метод `warmCache`:

```java
@Component
@RequiredArgsConstructor
public class CaffeineUserAccessProvider implements UserAccessProvider {
    // ... существующий код из §5.4 ...

    /** Догрев кеша после ручной загрузки UserAggregate (см. UserAccessContextLoader.loadFor). */
    public void warmCache(long typeId, String idRaw, AccessMetric metric) {
        cache.put(typeId + ":" + idRaw, metric);
    }
}
```

Bootstrap-контекст **не утекает** наружу — он валиден только внутри `try-with-resources`. После загрузки строится «настоящий» `AccessContext` с правами загруженного пользователя.

### 7.5. 2-stage миграция: schema-only Liquibase + data-seed после bootstrap'а

**Проблема:** Liquibase в Spring Boot 3.x запускается через `LiquibaseAutoConfiguration` **до** `SmartInitializingSingleton.afterSingletonsInstantiated()`. На этот момент `MetadataSnapshot` ещё не построен, `MapperRegistry`/`CallbackDispatcher`/`AccessFilterGraph` не готовы. Любая попытка вызвать service'ы из `CustomTaskChange.execute(...)` упрётся в `IllegalStateException("MetadataSnapshot not yet built")`.

**Решение** — разделить на два этапа:

#### Стадия 1: schema-only Liquibase (DDL до Spring DI)

```yaml
spring:
  liquibase:
    change-log: classpath:db/changelog/db.changelog-master.xml
    contexts: schema           # только DDL change-set'ы
```

`db.changelog-master.xml` подключает только schema-изменения; data-seed change-set'ы помечены `context="data-seed"` и **не выполняются** Liquibase'ом — они запускаются на стадии 2.

#### Стадия 2: `DataSeedRunner` после bootstrap'а

```java
public interface DataSeedTask {
    /** Уникальный ключ задачи. Идемпотентность обеспечивается записью в `data_seed_log`. */
    String taskKey();
    /** Выполняется в managed-транзакции под maxPrivileges()-контекстом. */
    void execute();
    /** SemVer; задача выполняется один раз для каждого (taskKey, version). */
    int version() default 1;
}

@Component
@RequiredArgsConstructor
@Order(Ordered.HIGHEST_PRECEDENCE + 2000)   // после MetadataBootstrapper, MapperRegistry, CallbackDispatcher
public class DataSeedRunner implements SmartInitializingSingleton {

    private final AccessContextHolder holder;
    private final SystemAccessContexts systems;
    private final List<DataSeedTask> tasks;
    private final TransactionTemplate tx;
    private final DataSeedLogRepository seedLog;

    @Override
    public void afterSingletonsInstantiated() {
        for (DataSeedTask task : tasks) {
            String key = task.taskKey();
            int v = task.version();
            if (seedLog.existsByKeyAndVersion(key, v)) continue;

            tx.executeWithoutResult(status -> {
                try (var ignored = holder.bind(systems.maxPrivileges())) {
                    task.execute();
                    seedLog.save(new DataSeedLogEntry(key, v, Instant.now()));
                }
            });
            log.info("DataSeedTask {} v{} applied", key, v);
        }
    }
}

@Entity @Table(name = "data_seed_log")
public class DataSeedLogEntry {
    @Id private String taskKey;
    @Id private int    version;
    private Instant appliedAt;
    // ... constructors / accessors
}
```

Пример задачи:

```java
@Component
@RequiredArgsConstructor
public class SeedDefaultCustomers implements DataSeedTask {
    private final CustomerService customers;

    @Override public String taskKey() { return "seed.customers.defaults"; }
    @Override public int    version() { return 1; }

    @Override public void execute() {
        customers.seedDefaults();   // mapper, validator, repo.save — всё работает
    }
}
```

#### ArchUnit-правило

```java
@ArchTest
static final ArchRule no_liquibase_custom_task_for_data_seed =
    noClasses().should().implement(liquibase.change.custom.CustomTaskChange.class)
               .orShould().implement(liquibase.change.custom.CustomChange.class);
```

Все data-seed'ы — только через `DataSeedTask`. ArchUnit проверяет, что `CustomTaskChange`/`CustomChange` нигде не реализованы.

#### Healthcheck

`MetadataHealthIndicator` (см. §25) проверяет наличие `MetadataSnapshot`. Дополнительно — `DataSeedHealthIndicator` блокирует readiness, пока не отработают все `DataSeedTask`'и. До завершения второй стадии трафик в pod не попадает.

```java
@Component
@RequiredArgsConstructor
public class DataSeedHealthIndicator implements HealthIndicator {
    private final DataSeedRunner runner;     // expose `boolean isCompleted()`

    @Override
    public Health health() {
        return runner.isCompleted()
            ? Health.up().withDetail("dataSeedCompleted", true).build()
            : Health.down().withDetail("dataSeedCompleted", false).build();
    }
}
```

---

## 8. Авторство и аудит изменений

### 8.1. Поля в `AbstractAggregate`

```java
@MappedSuperclass
@EntityListeners({AggregateLifecycleListener.class, AuditingEntityListener.class})
public abstract class AbstractAggregate<ID extends Serializable> {

    public abstract ID getId();

    @Embedded
    @AttributeOverride(name = "payload",
        column = @Column(name = "own_access", columnDefinition = "jsonb"))
    @FieldId(value = 1, defaultAccess = DefaultAccess.READ_ONLY)
    private AccessMetric ownAccess = AccessMetric.empty();

    @Version
    private long version;

    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private Instant updatedAt;

    @Embedded
    @Type(AggregateReferenceUuidUserType.class)
    @AttributeOverrides({
        @AttributeOverride(name = "targetTypeId", column = @Column(name = "created_by_type_id", updatable = false)),
        @AttributeOverride(name = "targetIdRaw",  column = @Column(name = "created_by_id",      updatable = false))
    })
    @CreatedBy
    @FieldId(value = 2, defaultAccess = DefaultAccess.READ_ONLY)
    private AggregateReference<UserAggregate, ?> createdBy;

    @Embedded
    @Type(AggregateReferenceUuidUserType.class)
    @AttributeOverrides({
        @AttributeOverride(name = "targetTypeId", column = @Column(name = "updated_by_type_id")),
        @AttributeOverride(name = "targetIdRaw",  column = @Column(name = "updated_by_id"))
    })
    @LastModifiedBy
    @FieldId(value = 3, defaultAccess = DefaultAccess.READ_ONLY)
    private AggregateReference<UserAggregate, ?> updatedBy;

    public AccessMetric ownAccess() { return ownAccess; }

    public void setOwnAccess(AccessMetric m) {
        this.ownAccess = (m == null) ? AccessMetric.empty() : m;
    }

    // -------- Доменные хуки (см. §16) --------
    protected void onPreFlush(LifecyclePhase phase) {}
    protected void onBeforeCommit(LifecyclePhase phase) {}
    protected void onAfterCommit(LifecyclePhase phase) {}

    // -------- Безопасный toString / debugDump --------
    @Override
    public String toString() {
        return SafeToString.render(this);
    }

    /**
     * Полное представление агрегата БЕЗ маскирования. Доступно ТОЛЬКО для root-пользователей
     * (требует ROOT_READ в переданном AccessContext); иначе — AccessDeniedException.
     * Использование: temporary debug logging, диагностические endpoint'ы под admin'скими
     * правами. Для обычного логирования используйте toString().
     */
    public String debugDump(AccessContext ctx) {
        return SafeToString.debugRender(this, ctx);
    }
}
```

`toString()` — **не final**. Subclass'ы могут переопределять, Lombok `@ToString` работает. По умолчанию вызывается `SafeToString.render`, которая null-safe и **всегда маскирует** даже для root-пользователей (это логи; root-debugger использует `debugDump(ctx)` отдельным вызовом):

```java
public final class SafeToString {

    private static volatile MetadataSnapshotProvider provider;

    public static void init(MetadataSnapshotProvider p) { provider = p; }

    /**
     * Маскирующее представление. ВСЕГДА скрывает HIDDEN/WRITE_ONLY поля, независимо от
     * текущего AccessContext. Используется в логах, исключениях, Hibernate show_sql.
     */
    public static String render(AbstractAggregate<?> agg) {
        var p = provider;
        if (p == null) return identityString(agg);
        try {
            var snap = p.staticGet().orElse(null);
            if (snap == null) return identityString(agg);
            long typeId = snap.typeIdOf(agg.getClass());
            var desc = snap.aggregate(typeId);
            StringBuilder sb = new StringBuilder(agg.getClass().getSimpleName());
            sb.append('{');
            sb.append("id=").append(agg.getId());
            for (FieldDescriptor fd : desc.fields()) {
                if (fd.parentFieldId() != -1) continue;     // только top-level
                if (fd.defaultAccess() == AccessLevel.HIDDEN
                    || fd.defaultAccess().mode() == WriteMode.WRITE_ONLY) {
                    sb.append(", ").append(fd.shortName()).append("=***");
                } else {
                    sb.append(", ").append(fd.shortName()).append('=').append(fd.read(agg));
                }
            }
            sb.append('}');
            return sb.toString();
        } catch (Exception e) {
            return identityString(agg);
        }
    }

    /**
     * Полное представление БЕЗ маскирования — только для root-пользователей.
     * Если у текущего ctx нет ROOT_READ глобального ИЛИ для конкретного типа — AccessDeniedException.
     */
    public static String debugRender(AbstractAggregate<?> agg, AccessContext ctx) {
        var p = provider;
        if (p == null) throw new IllegalStateException("MetadataSnapshot not yet built");
        var snap = p.get();
        long typeId = snap.typeIdOf(agg.getClass());

        var um = ctx.resolver().userMetricFor(ctx);
        int g = AccessFlags.expand(um.globalFlags());
        int t = AccessFlags.expand(um.typeFlags().getOrDefault(typeId, 0));
        boolean rootRead = ((g & AccessFlags.ROOT_READ) != 0) || ((t & AccessFlags.ROOT_READ) != 0);
        if (!rootRead) {
            throw new AccessDeniedException(
                "debugDump requires ROOT_READ for typeId=" + typeId);
        }

        var desc = snap.aggregate(typeId);
        StringBuilder sb = new StringBuilder(agg.getClass().getSimpleName()).append('{');
        sb.append("id=").append(agg.getId());
        for (FieldDescriptor fd : desc.fields()) {
            if (fd.parentFieldId() != -1) continue;
            sb.append(", ").append(fd.shortName()).append('=').append(fd.read(agg));
        }
        sb.append('}');
        return sb.toString();
    }

    private static String identityString(Object o) {
        return o.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(o));
    }
}
```

### 8.2. `AuditorAware` с SYSTEM-fallback

`AuditorAware` для Spring Data Auditing. Если на момент записи `AccessContext` отсутствует — это batch/migration-сценарий, и actor должен быть явно `SYSTEM_PRINCIPAL_ID`-ref (а не `null`), чтобы `@CreatedBy`/`@LastModifiedBy` всегда были заполнены.

```java
@Configuration
@EnableJpaAuditing(auditorAwareRef = "principalRefAuditor")
public class AuditingConfig {

    @Bean
    public AuditorAware<AggregateReference<UserAggregate, ?>> principalRefAuditor(
            AccessContextHolder holder, AggregateReferenceFactory refs) {
        return () -> {
            var ctx = holder.tryGet().orElse(null);
            if (ctx != null && ctx.principalRef() != null) {
                return Optional.of(ctx.principalRef());
            }
            // Системный fallback: всегда заполнено, никогда не null
            @SuppressWarnings("unchecked")
            var systemRef = (AggregateReference<UserAggregate, ?>)
                    refs.system(UserAggregate.class, AccessContext.SYSTEM_PRINCIPAL_ID);
            return Optional.of(systemRef);
        };
    }
}
```

### 8.3. Hibernate Envers — `RevisionEntity` с `principalRef`

```java
@Entity
@RevisionEntity(PrincipalRevisionListener.class)
@Table(name = "revinfo")
public class PrincipalRevision extends DefaultRevisionEntity {
    @Embedded
    @Type(AggregateReferenceUuidUserType.class)
    @AttributeOverrides({
        @AttributeOverride(name = "targetTypeId", column = @Column(name = "actor_type_id")),
        @AttributeOverride(name = "targetIdRaw",  column = @Column(name = "actor_id"))
    })
    private AggregateReference<UserAggregate, ?> actor;

    public AggregateReference<UserAggregate, ?> getActor() { return actor; }
    public void setActor(AggregateReference<UserAggregate, ?> a) { this.actor = a; }
}

@Component
@RequiredArgsConstructor
public class PrincipalRevisionListener implements RevisionListener {
    private final AccessContextHolder holder;
    private final AggregateReferenceFactory refs;

    @Override
    @SuppressWarnings("unchecked")
    public void newRevision(Object revision) {
        var ctx = holder.tryGet().orElse(null);
        AggregateReference<UserAggregate, ?> actor;
        if (ctx != null && ctx.principalRef() != null) {
            actor = (AggregateReference<UserAggregate, ?>) ctx.principalRef();
        } else {
            // Системные миграции/batch'и: явно SYSTEM_PRINCIPAL_ID, не null
            actor = (AggregateReference<UserAggregate, ?>)
                    refs.system(UserAggregate.class, AccessContext.SYSTEM_PRINCIPAL_ID);
        }
        ((PrincipalRevision) revision).setActor(actor);
    }
}
```

### 8.4. `AuditFacade` — единственный шлюз к Envers

Envers `*_AUD` таблицы **не получают** Hibernate `@Filter`. Прямой `AuditReader.find(Customer.class, ...)` — потенциальная утечка cross-tenant ревизий.

**Решение:** `AuditFacade` — единственный класс в системе, который имеет право обращаться к `AuditReader`. ArchUnit жёстко гарантирует это.

```java
@Service
@RequiredArgsConstructor
public class AuditFacade {

    private final EntityManager em;
    private final AccessContextHolder holder;
    private final MetadataSnapshotProvider snapshots;
    private final ClaimsExtractor claimsExtractor;
    private final RegistryAccess registry;

    /**
     * Чтение истории ревизий конкретного агрегата с обязательной access-проверкой.
     * Если у пользователя нет права видеть current-state — нет права видеть и историю.
     */
    public <A extends AbstractAggregate<?>> List<AuditRevision<A>> readHistory(
            Class<A> aggregateClass, Object id) {

        var ctx = holder.getOrThrow();
        long typeId = snapshots.get().typeIdOf(aggregateClass);

        // ГАРАНТИЯ: сначала загружаем current — этот load пройдёт через Hibernate Filter
        // и/или @PostLoadAccessCheck. Если у пользователя нет права видеть текущий —
        // пути к истории нет.
        @SuppressWarnings({"rawtypes", "unchecked"})
        AggregateRepository repo = registry.repositoryByTypeId(typeId);
        var current = ((AggregateRepository) repo).findById(id).orElse(null);
        if (current == null) {
            throw new EntityNotFoundException(aggregateClass.getSimpleName() + " " + id);
        }

        var reader = AuditReaderFactory.get(em);

        // Доп. фильтрация revisions по @AccessFiltered (если есть): применяем claim-фильтр
        // на JPQL-уровне, потому что Envers не делает это автоматически.
        var query = reader.createQuery()
                .forRevisionsOfEntity(aggregateClass, false, true)
                .add(AuditEntity.id().eq(id));

        var filters = snapshots.get().filtersForTypeId(typeId);
        for (AccessFilterDef f : filters) {
            if (canBypass(ctx, f)) continue;
            Set<String> claimValues = claimsExtractor.extract(ctx.auth(), f.userClaim().jwtName);
            if (claimValues.isEmpty()) {
                return List.of();   // deny-all
            }
            query.add(AuditEntity.property(f.filterField() + "_id").in(new ArrayList<>(claimValues)));
        }

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        return rows.stream()
                .map(r -> new AuditRevision<A>(
                        (A) r[0],
                        (PrincipalRevision) r[1],
                        (RevisionType) r[2]))
                .toList();
    }

    private boolean canBypass(AccessContext ctx, AccessFilterDef f) {
        var um = ctx.resolver().userMetricFor(ctx);
        int g = AccessFlags.expand(um.globalFlags());
        if ((g & (AccessFlags.ADMIN_READ | AccessFlags.ROOT_READ)) != 0) return true;
        int t = AccessFlags.expand(um.typeFlags().getOrDefault(f.referencedTypeId(), 0));
        if ((t & (AccessFlags.ADMIN_READ | AccessFlags.ROOT_READ)) != 0) return true;
        for (long tid : f.transitiveBypassTypeIds()) {
            int tf = AccessFlags.expand(um.typeFlags().getOrDefault(tid, 0));
            if ((tf & (AccessFlags.ADMIN_READ | AccessFlags.ROOT_READ)) != 0) return true;
        }
        return false;
    }

    public record AuditRevision<A>(A snapshot, PrincipalRevision rev, RevisionType type) {}
}
```

**ArchUnit-правило:**

```java
@ArchTest
static final ArchRule audit_reader_only_in_core_audit =
    noClasses().that().resideOutsideOfPackage("..core.audit..")
               .should().dependOnClassesThat()
               .haveFullyQualifiedName(AuditReader.class.getName())
               .orShould().dependOnClassesThat()
               .haveFullyQualifiedName(AuditQuery.class.getName())
               .orShould().dependOnClassesThat()
               .haveFullyQualifiedName(AuditReaderFactory.class.getName());
```

---

## 9. Аннотации

### 9.1. `UserClaim` — type-safe enum для имён JWT-claim'ов

```java
public enum UserClaim {
    ORG_IDS         ("orgIds"),
    CUSTOMER_IDS    ("customerIds"),
    PROJECT_IDS     ("projectIds"),
    DEPARTMENT_IDS  ("departmentIds");

    public final String jwtName;
    UserClaim(String n) { this.jwtName = n; }
}
```

### 9.2. `@TypeId`

```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface TypeId {
    long value();
    DefaultAccess defaultRepoAccess() default DefaultAccess.HIDDEN;
}
```

Ставится **и на агрегат, и на интерфейс репозитория** со совпадающим `value()`. Несовпадение — fail-fast на старте.

### 9.3. `@FieldId`

```java
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface FieldId {
    /** Глобально-уникальный неизменяемый ID поля (включая поля внутри @Embeddable). */
    long value();
    DefaultAccess defaultAccess() default DefaultAccess.HIDDEN;
    Class<?>[] groups() default {};
}
```

**Глобальная уникальность `value()` валидируется рекурсивно** — включая поля внутри `@Embeddable`/`@ElementCollection` (см. §24, fail-fast contract #2 и #9).

### 9.4. `@AggregateRepository` — маркер для AOP-pointcut'а

```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AggregateRepository {}
```

Все репозитории-наследники `core.AggregateRepository` обязаны нести этот маркер. AOP `@Pointcut` использует **аннотацию**, не hardcoded-package:

```java
@Pointcut("@within(com.example.core.ddd.AggregateRepository) || " +
          "this(com.example.core.ddd.AggregateRepository)")
public void anyAggregateRepoBean() {}

@Pointcut("execution(* find*(..)) || execution(* count(..)) || execution(* count*(..)) || " +
          "execution(* exists*(..)) || execution(* search*(..))")
public void readMethod() {}
```

Это работает независимо от пакета приложения.

### 9.5. `@AccessFiltered`

```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(AccessFiltered.List.class)
public @interface AccessFiltered {
    /** Имя property агрегата, по которому фильтруется. Должно соответствовать regex
     *  [a-zA-Z_][a-zA-Z0-9_]*. Bootstrap fail-fast при нарушении. */
    String filterField();
    UserClaim userClaim();
    long referencedTypeId();
    String filterName() default "";
    BypassPolicy bypassPolicy() default BypassPolicy.EXPLICIT_ONLY;

    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    @interface List { AccessFiltered[] value(); }
}
```

### 9.6. `@ValidAggregateRef`

```java
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ValidAggregateRefValidator.class)
public @interface ValidAggregateRef {
    Class<? extends AbstractAggregate<?>> target();
    Class<? extends Serializable> idType();

    String message() default "invalid aggregate reference";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
```

### 9.7. `@AccessChecked`

```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AccessChecked {
    /**
     * strict=true (по умолчанию):
     *   при попытке write-операции на агрегат БЕЗ AccessContext в потоке —
     *   AccessAwarePreUpdateListener бросает AccessDeniedException.
     *
     * strict=false:
     *   при отсутствии AccessContext проверки молча пропускаются. ТОЛЬКО для системных агрегатов.
     */
    boolean strict() default true;
}
```

### 9.8. `@PostLoadAccessCheck`

```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface PostLoadAccessCheck {
    /**
     * Если true (по умолчанию), при загрузке entity через find/L1-hit
     * AccessAwarePostLoadInspector проверяет, что значения filterField'ов
     * содержатся в claim'ах пользователя. При несовпадении — AccessDeniedException.
     * НЕ модифицирует managed-entity, только бросает.
     *
     * Поведение по умолчанию для @AccessFiltered-агрегатов:
     *   - если аннотация @PostLoadAccessCheck НЕ проставлена → подразумевается ВКЛЮЧЕНО
     *     (управляется глобальным toggle app.ddd.access.implicit-post-load-check, default true);
     *   - явное @PostLoadAccessCheck(false) → ОТКЛЮЧЕНО (требуется комментарий-обоснование);
     *   - явное @PostLoadAccessCheck → ВКЛЮЧЕНО.
     */
    boolean value() default true;
}
```

### 9.9. `@AggregateLockingPolicy`

```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface AggregateLockingPolicy {
    LockMode value() default LockMode.OPTIMISTIC;
    long pessimisticTimeoutMs() default 3000;

    enum LockMode {
        NONE,
        OPTIMISTIC,
        OPTIMISTIC_FORCE_INCREMENT,
        PESSIMISTIC_READ,
        PESSIMISTIC_WRITE
    }
}
```

### 9.10. `@CacheInvalidationPolicy`

```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface CacheInvalidationPolicy {
    /** По умолчанию ASYNC. Никаких блокировок горячего пути. */
    boolean async() default true;
    int     maxLagMs() default 100;
}
```

### 9.11. `@AccessProjected`

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AccessProjected {}
```

Применяется на REST-методы; `AccessProjectionAdvice` (§19) перехватывает ответ и оборачивает в envelope с `_access` и `_accessOverrides`.

### 9.12. `@PrefetchDepth` — конфигурируемая глубина batch-prefetch'а

```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface PrefetchDepth {
    /** Максимальная глубина обхода для RefBatchPrefetcher. Default = 3.
     *  При превышении инкрементится метрика ddd.refprefetch.truncated{typeId}. */
    int value() default 3;
}
```

### 9.13. `@DomainEvent` — стабильное имя события

```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface DomainEvent {
    /**
     * Стабильное имя события для outbox/Kafka header'ов.
     * Должно содержать версию: "OrderPlaced.v1", "ChargeRequested.v2".
     * Уникальность валидируется на bootstrap'е.
     */
    String stableName();

    /** Бизнес-версия. Используется в outbox event_version колонке. */
    int version() default 1;
}
```

### 9.14. Hibernate-native `@SoftDelete`

Используется `org.hibernate.annotations.SoftDelete` (Hibernate 6.4+) — подробности в §23.

---
## 10. AbstractAggregate, generic ID, IdCodec

### 10.1. Конкретный агрегат

```java
@Entity
@Table(name = "customer_aggregate")
@TypeId(value = 1001, defaultRepoAccess = DefaultAccess.READ_ONLY)
@AccessFiltered(filterField = "organization", userClaim = UserClaim.ORG_IDS,
                referencedTypeId = 2001, bypassPolicy = BypassPolicy.AUTO_TRANSITIVE)
@AccessChecked
@AggregateLockingPolicy(LockMode.OPTIMISTIC)
@PrefetchDepth(3)
@org.hibernate.annotations.SoftDelete(columnName = "deleted")
public class CustomerAggregate extends AbstractAggregate<UUID> {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid")
    @FieldId(value = 1000, defaultAccess = DefaultAccess.READ_ONLY)
    private UUID id;

    @FieldId(value = 1010, defaultAccess = DefaultAccess.READ_WRITE)
    @NotBlank @Size(max = 200)
    private String displayName;

    @FieldId(value = 1011, defaultAccess = DefaultAccess.READ_ONLY)
    @Email
    private String email;

    @FieldId(value = 1012, defaultAccess = DefaultAccess.WRITE_ONLY)
    private String passwordHash;

    /** Init-once: организация задаётся при онбординге, дальше read-only без admin/root. */
    @Embedded
    @Type(AggregateReferenceUuidUserType.class)
    @AttributeOverrides({
        @AttributeOverride(name = "targetTypeId", column = @Column(name = "org_type_id")),
        @AttributeOverride(name = "targetIdRaw",  column = @Column(name = "org_id"))
    })
    @FieldId(value = 1014, defaultAccess = DefaultAccess.INIT_ONCE)
    @ValidAggregateRef(target = OrganizationAggregate.class, idType = UUID.class)
    private AggregateReference<OrganizationAggregate, UUID> organization;

    /** Внутренняя коллекция — часть агрегата, не отдельный агрегат. */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "customer_cookies",
        joinColumns = @JoinColumn(name = "customer_id"))
    @FieldId(value = 1013, defaultAccess = DefaultAccess.HIDDEN)
    @Valid
    private List<CookieRecord> cookies = new ArrayList<>();

    @Override public UUID getId() { return id; }

    @Override
    protected void onBeforeCommit(LifecyclePhase phase) {
        if (phase == LifecyclePhase.UPDATE && passwordHash == null)
            throw new InvalidAggregateStateException("passwordHash is required");
    }
}
```

### 10.2. `@Embeddable` с собственными `@FieldId`-полями (включая вложенные)

Внутренние `@Embeddable`-типы обязаны размечать **свои** поля собственными `@FieldId`. `MetadataBootstrapper` валидирует это рекурсивно. Уникальность `@FieldId.value()` — глобальная (включая embedded). Поддерживается **произвольная глубина вложенности** embedded'ов: `Customer → cookies: List<CookieRecord> → security: SecuritySettings → ...`.

```java
@Embeddable
public class CookieRecord {

    @FieldId(value = 10130001, defaultAccess = DefaultAccess.READ_ONLY)
    @NotBlank @Size(max = 100)
    private String name;

    @FieldId(value = 10130002, defaultAccess = DefaultAccess.HIDDEN)
    @NotBlank
    private String sessionToken;

    @FieldId(value = 10130003, defaultAccess = DefaultAccess.READ_ONLY)
    private Instant expiresAt;

    @Embedded
    @FieldId(value = 10130004, defaultAccess = DefaultAccess.HIDDEN)
    private SecuritySettings security;
}

@Embeddable
public class SecuritySettings {
    @FieldId(value = 10130100, defaultAccess = DefaultAccess.HIDDEN)
    private String ipAddress;

    @FieldId(value = 10130101, defaultAccess = DefaultAccess.READ_ONLY)
    private boolean secure;
}
```

Семантика: эффективный уровень для embedded-поля — **пересечение** уровней всех родительских `@FieldId` от корня до самого поля.

### 10.3. `IdCodec` — универсальное кодирование ID

```java
public final class IdCodec {
    private IdCodec() {}

    public static String encode(Object id) {
        return switch (id) {
            case null -> throw new IllegalArgumentException("id is null");
            case UUID u    -> u.toString();
            case Long l    -> l.toString();
            case Integer i -> i.toString();
            case String s  -> s;
            case Tsid t    -> t.toString();
            default -> throw new UnsupportedIdException(id.getClass());
        };
    }

    @SuppressWarnings("unchecked")
    public static <ID> ID decode(String raw, Class<ID> type) {
        if (type == UUID.class)    return (ID) UUID.fromString(raw);
        if (type == Long.class)    return (ID) Long.valueOf(raw);
        if (type == Integer.class) return (ID) Integer.valueOf(raw);
        if (type == String.class)  return (ID) raw;
        if (type == Tsid.class)    return (ID) Tsid.from(raw);
        throw new UnsupportedIdException(type);
    }

    public static <ID> Optional<ID> tryDecode(String raw, Class<ID> type) {
        try { return Optional.of(decode(raw, type)); }
        catch (RuntimeException e) { return Optional.empty(); }
    }
}
```

### 10.4. `AggregateIdGenerator`

```java
public class AggregateIdGenerator implements IdentifierGenerator, Configurable {
    private IdStrategy strategy;

    @Override
    public void configure(Type type, Properties params, ServiceRegistry sr) {
        Class<?> javaType = type.getReturnedClass();
        if (javaType == UUID.class)         strategy = new UuidV7Strategy();
        else if (javaType == String.class)  strategy = new TsidStringStrategy();
        else if (javaType == Long.class || javaType == long.class)
                                            strategy = new SnowflakeStrategy();
        else throw new MappingException("Unsupported id type: " + javaType);
    }

    @Override public Object generate(SharedSessionContractImplementor s, Object o) {
        return strategy.next();
    }
}
```

### 10.5. `findByIdLocked` с поддержкой `OPTIMISTIC_FORCE_INCREMENT`

```java
default Optional<T> findByIdLocked(ID id) {
    LockingPolicySpec spec = lockingPolicySpecOrDefault();
    LockModeType jpaMode = switch (spec.mode()) {
        case OPTIMISTIC                  -> LockModeType.OPTIMISTIC;
        case OPTIMISTIC_FORCE_INCREMENT  -> LockModeType.OPTIMISTIC_FORCE_INCREMENT;
        case PESSIMISTIC_READ            -> LockModeType.PESSIMISTIC_READ;
        case PESSIMISTIC_WRITE           -> LockModeType.PESSIMISTIC_WRITE;
        case NONE                        -> LockModeType.NONE;
    };
    Map<String, Object> hints = new HashMap<>();
    if (spec.mode() == AggregateLockingPolicy.LockMode.PESSIMISTIC_WRITE) {
        hints.put("javax.persistence.lock.timeout", spec.timeoutMs());
    }
    return Optional.ofNullable(em().find(domainClass(), id, jpaMode, hints));
}
```

`UserAggregate` использует `OPTIMISTIC_FORCE_INCREMENT`, чтобы конкурентные `grantRole`/`grantTypeFlags` сериализовались:

```java
@Entity
@TypeId(value = 9001)
@AggregateLockingPolicy(LockMode.OPTIMISTIC_FORCE_INCREMENT)
public class UserAggregate extends AbstractAggregate<UUID> { ... }
```

### 10.6. `@ElementCollection` field-level access — явная семантика

Коллекции `@Embeddable`-элементов (`@ElementCollection`) — частый случай в составе агрегата (cookies-история, attachments, settings-list). Поведение field-level access описано **явно** во избежание UB:

| Уровень | Что контролирует | Как объявляется |
|---|---|---|
| **Collection-as-whole** | Виден ли сам факт наличия коллекции (вернуть `[]` / `null` / встроенное содержимое) | `@FieldId(...)` на самом collection-поле в агрегате |
| **Element fields** | Какие поля внутри `@Embeddable`-элемента отдаются | `@FieldId(...)` на полях `@Embeddable`-класса |

**Правило эффективного уровня для поля внутри элемента коллекции:**

```
effective(elementField) = collection.defaultAccess
                        ∩ elementField.defaultAccess
                        ∩ GlobalGrants[typeId, elementField.fieldId]
```

То есть сначала пересекаем уровень самой коллекции (если коллекция HIDDEN — всё содержимое HIDDEN), потом — уровень конкретного поля элемента.

#### Outbound (Jackson сериализация)

Реализация — в `AccessAwareEmbeddableWriter.serializeCollectionAsField` (см. §12.5). Алгоритм:

1. Резолвим уровень коллекции через `ctx.resolve(aggTypeId, collectionFieldId, null)`.
2. Если `!canRead()` — пишем `null` (или пустой массив для не-null коллекций — выбираем `null` как явный сигнал «нет доступа»).
3. Иначе — `push(collectionFieldId)` в path-stack из §12.5, итерируем элементы. Для каждого элемента сериализатор `@Embeddable`-типа использует тот же path-stack — `@FieldId` на полях элемента резолвится как embedded-в-embedded-поле, эффективный уровень = пересечение всех родительских.

```java
// Внутри AccessAwareEmbeddableWriter, ветка для коллекций:
@Override
public void serializeAsField(Object bean, JsonGenerator g, SerializerProvider sp) throws Exception {
    Object aggTypeIdObj = sp.getAttribute(AccessAwareWriter.CTX_AGGREGATE_TYPEID);
    @SuppressWarnings("unchecked")
    Deque<Long> stack = (Deque<Long>) sp.getAttribute(AccessAwareWriter.CTX_PATH_STACK);
    if (aggTypeIdObj == null || stack == null) { super.serializeAsField(bean, g, sp); return; }
    long aggTypeId = (Long) aggTypeIdObj;

    FieldDescriptor fd = snapshots.get().aggregate(aggTypeId).fieldByPath(stack, propertyName);
    if (fd == null) { g.writeNullField(getName()); return; }

    var ctxOpt = holder.tryGet();
    AccessContext ctx = ctxOpt.orElse(null);
    AccessLevel collLvl = (ctx != null) ? computeEffective(stack, fd, aggTypeId, ctx) : fd.defaultAccess();
    if (!collLvl.canRead()) { g.writeNullField(getName()); return; }

    Object value = base.get(bean);
    // Если поле — Collection of @Embeddable: push fieldId, итерируем стандартным сериализатором
    if (value instanceof Collection<?> coll && fd.isElementCollection()) {
        stack.push(fd.fieldId());
        try {
            g.writeFieldName(getName());
            g.writeStartArray();
            for (var elem : coll) {
                if (elem == null) { g.writeNull(); continue; }
                sp.findValueSerializer(elem.getClass()).serialize(elem, g, sp);
            }
            g.writeEndArray();
        } finally {
            stack.pop();
        }
        return;
    }
    // Иначе — стандартная ветка для @Embedded (см. оригинал §12.5)
    pushAndSerialize(bean, g, sp, fd, stack);
}
```

#### Inbound (MapStruct `mergeInto`)

Если `@FieldId` на collection-поле даёт `!canWrite(currentValue)` — `MapStruct.applyInbound` в `AccessAwareMappingHelper` (§20.5) откатывает поле на оригинал (или бросает в strict-режиме) **целиком**. Ниже element-level access **не применяется** — collection либо принимается полностью, либо отвергается. Это упрощает семантику: либо клиент имеет право редактировать список, либо нет.

> **Если требуется per-element write-control** — это уже не `@ElementCollection`, а отдельный child-aggregate с `@TypeId` и собственным репозиторием/сервисом.

#### Bootstrap-валидация

`MetadataBootstrapper` (см. §24) дополнительно проверяет:
- 22. Поле `@ElementCollection` имеет `@FieldId` (как и любое другое persistent-поле агрегата).
- 23. Если поле — `@ElementCollection` от `@Embeddable`-типа, у `@Embeddable`-типа есть как минимум один `@FieldId` (иначе всё содержимое будет невидимым по deny-by-default).

Соответствующие fail-fast сообщения добавлены в §24.7.

---

## 11. AggregateReference с типизированными колонками

### 11.1. Структура

`AggregateReference` — это `@Embeddable`, хранящаяся в **двух** колонках:
- `targetTypeId` — `BIGINT NOT NULL`,
- `targetIdRaw` — **типизированная колонка** в зависимости от `idType` (UUID / BIGINT / VARCHAR), не unified `VARCHAR(128)`.

```java
@Embeddable
@EqualsAndHashCode(of = {"targetTypeId", "targetIdRaw"})
public final class AggregateReference<T extends AbstractAggregate<ID>,
                                      ID extends Serializable>
        implements Serializable {

    @Column(name = "ref_type_id", nullable = false)
    private long targetTypeId;

    /**
     * Реальная колонка определяется через @Type(AggregateReferenceXxxUserType.class) +
     * @AttributeOverride на конкретном поле агрегата.
     * Тип Java здесь — String для унифицированного API, но UserType маппит в native UUID/Long/...
     */
    @Column(name = "ref_id", nullable = false)
    private String targetIdRaw;

    protected AggregateReference() {}

    public static <T extends AbstractAggregate<ID>, ID extends Serializable>
           AggregateReference<T, ID> ofRaw(long typeId, String idRaw) {
        var r = new AggregateReference<T, ID>();
        r.targetTypeId = typeId;
        r.targetIdRaw  = idRaw;
        return r;
    }

    public long targetTypeId()    { return targetTypeId; }
    public String targetIdRaw()   { return targetIdRaw; }
}
```

### 11.2. Семейство `AggregateReferenceUserType`-классов

Внешний API сохранён: `AggregateReferenceUuidUserType`, `AggregateReferenceLongUserType`, `AggregateReferenceStringUserType`, `AggregateReferenceTsidUserType`. Изменены **внутренние** сигнатуры под Hibernate 6 API + mapping-классы реально аннотированы `@Embeddable` (это требование Hibernate 6 для embeddable-mapping в `CompositeUserType.embeddable()`):

```java
public abstract class AbstractAggregateReferenceUserType<ID extends Serializable>
        implements CompositeUserType<AggregateReference<?, ?>> {

    /** Mapping-class — реально аннотированный @Embeddable. */
    protected abstract Class<?> mappingEmbeddable();
    /** Конкретный Java-тип id-колонки (UUID/Long/String/Tsid). */
    protected abstract Class<ID> idClass();

    @Override
    public Object getPropertyValue(AggregateReference<?, ?> component, int property) {
        return switch (property) {
            case 0 -> component.targetTypeId();
            case 1 -> decodeForColumn(component.targetIdRaw());
            default -> throw new HibernateException("Unknown property index: " + property);
        };
    }

    @Override
    public AggregateReference<?, ?> instantiate(ValueAccess values, SessionFactoryImplementor sf) {
        Long typeId = values.getValue(0, Long.class);
        Object id   = values.getValue(1, idClass());
        if (typeId == null || id == null) return null;
        return AggregateReference.ofRaw(typeId, IdCodec.encode(id));
    }

    @Override public Class<?> embeddable()     { return mappingEmbeddable(); }
    @Override public Class<?> returnedClass()  { return AggregateReference.class; }

    @Override
    public boolean equals(AggregateReference<?, ?> a, AggregateReference<?, ?> b) {
        return Objects.equals(a, b);
    }

    @Override public int hashCode(AggregateReference<?, ?> r) { return r == null ? 0 : r.hashCode(); }
    @Override public AggregateReference<?, ?> deepCopy(AggregateReference<?, ?> v) { return v; }   // immutable
    @Override public boolean isMutable() { return false; }

    // КОРРЕКТНЫЕ Hibernate 6 сигнатуры: SharedSessionContractImplementor-параметр.
    @Override
    public Serializable disassemble(AggregateReference<?, ?> v, SharedSessionContractImplementor s) {
        return v;
    }
    @Override
    public AggregateReference<?, ?> assemble(Serializable s, SharedSessionContractImplementor sess, Object owner) {
        return (AggregateReference<?, ?>) s;
    }
    @Override
    public AggregateReference<?, ?> replace(AggregateReference<?, ?> det, AggregateReference<?, ?> man,
                                            SharedSessionContractImplementor s, Object owner) {
        return det;
    }

    @SuppressWarnings("unchecked")
    private ID decodeForColumn(String raw) {
        if (raw == null) return null;
        return (ID) IdCodec.decode(raw, idClass());
    }
}
```

Mapping-классы — **реально `@Embeddable`** (геттеры/сеттеры обязательны для CompositeUserType-introspection'а Hibernate 6):

```java
@Embeddable
public class AggregateReferenceUuidState {
    @Column(name = "target_type_id", nullable = false)
    private long targetTypeId;

    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "target_id_raw",  nullable = false)
    private UUID targetIdRaw;

    public long getTargetTypeId()       { return targetTypeId; }
    public void setTargetTypeId(long v) { this.targetTypeId = v; }
    public UUID getTargetIdRaw()        { return targetIdRaw;  }
    public void setTargetIdRaw(UUID v)  { this.targetIdRaw = v; }
}

@Embeddable
public class AggregateReferenceLongState {
    @Column(name = "target_type_id", nullable = false)
    private long targetTypeId;

    @JdbcTypeCode(SqlTypes.BIGINT)
    @Column(name = "target_id_raw",  nullable = false)
    private Long targetIdRaw;

    public long getTargetTypeId()       { return targetTypeId; }
    public void setTargetTypeId(long v) { this.targetTypeId = v; }
    public Long getTargetIdRaw()        { return targetIdRaw;  }
    public void setTargetIdRaw(Long v)  { this.targetIdRaw = v; }
}

@Embeddable
public class AggregateReferenceStringState {
    @Column(name = "target_type_id", nullable = false)
    private long targetTypeId;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "target_id_raw",  nullable = false)
    private String targetIdRaw;

    public long getTargetTypeId()         { return targetTypeId; }
    public void setTargetTypeId(long v)   { this.targetTypeId = v; }
    public String getTargetIdRaw()        { return targetIdRaw;  }
    public void setTargetIdRaw(String v)  { this.targetIdRaw = v; }
}

@Embeddable
public class AggregateReferenceTsidState {
    @Column(name = "target_type_id", nullable = false)
    private long targetTypeId;

    @JdbcTypeCode(SqlTypes.BIGINT)
    @Column(name = "target_id_raw",  nullable = false)
    private Long targetIdRaw;

    public long getTargetTypeId()       { return targetTypeId; }
    public void setTargetTypeId(long v) { this.targetTypeId = v; }
    public Long getTargetIdRaw()        { return targetIdRaw;  }
    public void setTargetIdRaw(Long v)  { this.targetIdRaw = v; }
}
```

Конкретные `UserType`-классы:

```java
public class AggregateReferenceUuidUserType extends AbstractAggregateReferenceUserType<UUID> {
    @Override protected Class<?> mappingEmbeddable() { return AggregateReferenceUuidState.class; }
    @Override protected Class<UUID> idClass()        { return UUID.class; }
}

public class AggregateReferenceLongUserType extends AbstractAggregateReferenceUserType<Long> {
    @Override protected Class<?> mappingEmbeddable() { return AggregateReferenceLongState.class; }
    @Override protected Class<Long> idClass()        { return Long.class; }
}

public class AggregateReferenceStringUserType extends AbstractAggregateReferenceUserType<String> {
    @Override protected Class<?> mappingEmbeddable() { return AggregateReferenceStringState.class; }
    @Override protected Class<String> idClass()      { return String.class; }
}

public class AggregateReferenceTsidUserType extends AbstractAggregateReferenceUserType<Tsid> {
    @Override protected Class<?> mappingEmbeddable() { return AggregateReferenceTsidState.class; }
    @Override protected Class<Tsid> idClass()        { return Tsid.class; }
}
```

> **Изменения G3:** убрано неиспользуемое `idSqlType()`; `embeddable()` возвращает класс с `@Embeddable`-аннотацией; `disassemble`/`assemble`/`replace` соответствуют сигнатуре Hibernate 6 (`SharedSessionContractImplementor`-параметр); внешний API не изменён.

#### Контракт-тест (обязательный)

```java
@DataJpaTest
class AggregateReferenceUuidUserTypeIT {
    @Autowired EntityManager em;
    @Autowired AggregateReferenceFactory refs;

    @Test
    void roundTripsUuidThroughNativeUuidColumn() {
        var c = new CustomerAggregate();
        c.setOrganization(refs.of(OrganizationAggregate.class, UUID.randomUUID()));
        em.persist(c); em.flush(); em.clear();

        var loaded = em.find(CustomerAggregate.class, c.getId());
        assertThat(loaded.getOrganization().targetIdRaw())
            .isEqualTo(c.getOrganization().targetIdRaw());

        // Проверка native-типа на уровне БД:
        var raw = em.createNativeQuery(
                "SELECT pg_typeof(org_id)::text FROM customer_aggregate WHERE id = :id")
            .setParameter("id", c.getId())
            .getSingleResult();
        assertThat(raw).isEqualTo("uuid");
    }
}
```

### 11.3. Использование на поле агрегата

```java
@Embedded
@Type(AggregateReferenceUuidUserType.class)
@AttributeOverrides({
    @AttributeOverride(name = "targetTypeId", column = @Column(name = "org_type_id")),
    @AttributeOverride(name = "targetIdRaw",  column = @Column(name = "org_id"))
})
@FieldId(value = 1014, defaultAccess = DefaultAccess.INIT_ONCE)
@ValidAggregateRef(target = OrganizationAggregate.class, idType = UUID.class)
private AggregateReference<OrganizationAggregate, UUID> organization;
```

Полученная DDL:

```sql
CREATE TABLE customer_aggregate (
    id            UUID    PRIMARY KEY,
    org_type_id   BIGINT  NOT NULL,
    org_id        UUID    NOT NULL,
    ...
    FOREIGN KEY (org_id) REFERENCES organization_aggregate (id),
    CONSTRAINT chk_org_type_id CHECK (org_type_id = 2001)
);

CREATE INDEX customer_org_ref_idx ON customer_aggregate (org_type_id, org_id);
```

**Преимущества:**
- FK на native-типизированный PK — целостность БД enforced.
- JOIN'ы без implicit-cast'а: `JOIN organization o ON c.org_id = o.id` — index-aware, plan-stable.
- Hibernate Filter `condition = "org_id IN (:orgIds)"` работает с типизированным parameter list (UUID[], не VARCHAR[]).

**Bootstrap-валидация:** для каждого `AggregateReference`-поля сверяется, что `@Type(...)` совпадает с типом, выводимым из `@ValidAggregateRef.idType()`. Несовпадение → fail-fast.

### 11.4. `AggregateReferenceFactory` — DI-фабрика

```java
@Component
@RequiredArgsConstructor
public class AggregateReferenceFactory {

    private final MetadataSnapshotProvider snapshots;

    public <T extends AbstractAggregate<ID>, ID extends Serializable>
           AggregateReference<T, ID> of(Class<T> targetClass, ID id) {
        long tid = snapshots.get().typeIdOf(targetClass);
        return AggregateReference.ofRaw(tid, IdCodec.encode(id));
    }

    public <T extends AbstractAggregate<?>>
           AggregateReference<T, ?> system(Class<T> targetClass, String systemId) {
        long tid = snapshots.get().typeIdOf(targetClass);
        return AggregateReference.ofRaw(tid, systemId);
    }

    public AggregateReference<?, ?> ofRaw(long typeId, String idRaw) {
        return AggregateReference.ofRaw(typeId, idRaw);
    }
}
```

### 11.5. Стирание типов: runtime-стратегия

Compile-time гарантий типа целевого агрегата нет. Защита — **runtime через четыре механизма**:

1. `@ValidAggregateRef(target=..., idType=...)` обязателен — fail-fast при отсутствии.
2. `MetadataSnapshot` хранит двунаправленный mapping `Class ↔ typeId` и `typeId → idClass`.
3. На старте `MetadataBootstrapper` сверяет:
   - `idType` из аннотации с реальным `@Id`-полем целевого класса;
   - `@Type(...)` (UserType) с idType.
4. `ValidAggregateRefValidator` проверяет `targetTypeId` и декодируемость `targetIdRaw`:

```java
public class ValidAggregateRefValidator
        implements ConstraintValidator<ValidAggregateRef, AggregateReference<?, ?>> {

    @Autowired private MetadataSnapshotProvider snapshots;
    private long expectedTypeId;
    private Class<? extends Serializable> expectedIdType;

    @Override public void initialize(ValidAggregateRef a) {
        expectedTypeId  = snapshots.get().typeIdOf(a.target());
        expectedIdType  = a.idType();
    }

    @Override public boolean isValid(AggregateReference<?, ?> v,
                                     ConstraintValidatorContext ctx) {
        if (v == null) return true;
        if (v.targetTypeId() != expectedTypeId) return false;
        return IdCodec.tryDecode(v.targetIdRaw(), expectedIdType).isPresent();
    }
}
```

### 11.6. Java records — ограничение

`AbstractAggregate` — это `@MappedSuperclass`. Java 21 records — `final`, не могут наследоваться. Поэтому **агрегаты — это классы**, не records. Иммутабельные value-типы внутри агрегата (например, `AccessMetricPayload`) — могут быть records.

---

## 12. Jackson — три ObjectMapper'а

### 12.1. Зачем три

В системе три различных контекста сериализации, каждый со своими требованиями:

| Mapper | Modifier | Назначение |
|---|---|---|
| `outboxObjectMapper` | **БЕЗ** `AccessAwareSerializerModifier` | Outbox payload, Axon Saga state, snapshot для `applyInbound` — сохраняем **полное** состояние независимо от прав |
| `accessAwareObjectMapper` | **С** `AccessAwareSerializerModifier` + `AggregateReferenceSerializer` | HTTP responses, `AccessProjectionService`, любой output для пользователя |
| `dtoObjectMapper` | **БЕЗ** modifier'а, но **С** `valueToTree`-friendly настройками | Конвертация DTO в `JsonNode` для cache'й, тестовая эквивалентность |

### 12.2. Конфигурация

```java
@Configuration
public class ObjectMapperConfig {

    @Bean(name = "outboxObjectMapper")
    public ObjectMapper outboxObjectMapper() {
        ObjectMapper m = JsonMapper.builder()
            .findAndAddModules()
            .build();
        m.registerModule(new JavaTimeModule());
        m.registerModule(new ParameterNamesModule());
        // НЕ регистрируем AccessAwareSerializerModifier — сохраняем full state.
        return m;
    }

    @Bean(name = "accessAwareObjectMapper")
    @Primary
    public ObjectMapper accessAwareObjectMapper(
            AccessAwareSerializerModifier accessModifier,
            AggregateReferenceSerializer aggRefSerializer,
            AggregateReferenceDeserializer aggRefDeserializer) {
        ObjectMapper m = JsonMapper.builder().build();
        m.registerModule(new JavaTimeModule());
        m.registerModule(new ParameterNamesModule());

        var module = new SimpleModule()
                .setSerializerModifier(accessModifier)
                .addSerializer(AggregateReference.class, aggRefSerializer)
                .addDeserializer(AggregateReference.class, aggRefDeserializer);
        m.registerModule(module);
        return m;
    }

    @Bean(name = "dtoObjectMapper")
    public ObjectMapper dtoObjectMapper() {
        ObjectMapper m = JsonMapper.builder().build();
        m.registerModule(new JavaTimeModule());
        m.registerModule(new ParameterNamesModule());
        return m;
    }

    /** Spring MVC использует @Primary accessAwareObjectMapper. */
    @Bean
    public MappingJackson2HttpMessageConverter httpConverter(
            @Qualifier("accessAwareObjectMapper") ObjectMapper m) {
        return new MappingJackson2HttpMessageConverter(m);
    }
}
```

### 12.3. `AccessAwareSerializerModifier` — резолвинг через `MetadataSnapshot`

```java
@Component
@RequiredArgsConstructor
public class AccessAwareSerializerModifier extends BeanSerializerModifier {

    private final MetadataSnapshotProvider snapshots;
    private final AccessContextHolder holder;

    @Override
    public List<BeanPropertyWriter> changeProperties(SerializationConfig cfg,
                                                     BeanDescription beanDesc,
                                                     List<BeanPropertyWriter> writers) {
        Class<?> beanType = beanDesc.getBeanClass();

        if (AbstractAggregate.class.isAssignableFrom(beanType)) {
            return wrapForAggregate(beanType, writers);
        }
        if (beanType.isAnnotationPresent(Embeddable.class)) {
            return wrapForEmbeddable(beanType, writers);
        }
        return writers;
    }

    private List<BeanPropertyWriter> wrapForAggregate(Class<?> beanType,
                                                      List<BeanPropertyWriter> writers) {
        long typeId = snapshots.get().typeIdOf(beanType);
        AggregateDescriptor desc = snapshots.get().aggregate(typeId);

        return writers.stream()
            .map(w -> {
                FieldDescriptor fd = desc.fieldByPropertyName(w.getName());
                if (fd == null) return null;     // не размечено @FieldId — скрыть
                return new AccessAwareWriter(w, typeId, fd, holder, snapshots);
            })
            .filter(Objects::nonNull)
            .toList();
    }

    /**
     * Для @Embeddable-типов: они могут быть переиспользованы в разных агрегатах с разными
     * родительскими fieldId. Поэтому resolveContext делает per-call lookup через стек path'ов
     * в SerializerProvider.attributes (см. AccessAwareEmbeddableWriter).
     */
    private List<BeanPropertyWriter> wrapForEmbeddable(Class<?> beanType,
                                                       List<BeanPropertyWriter> writers) {
        return writers.stream()
            .map(w -> new AccessAwareEmbeddableWriter(w, beanType, holder, snapshots))
            .filter(Objects::nonNull)
            .map(BeanPropertyWriter.class::cast)
            .toList();
    }
}
```

### 12.4. `AccessAwareWriter` — для top-level агрегатов и top-level embedded-полей

`AccessAwareWriter` пушит **стек path'ов** в `SerializerProvider.attributes` перед делегацией в `super.serializeAsField`. Стек вместо одиночного `parentFieldId` — это критично для произвольной глубины embedded'ов.

```java
public class AccessAwareWriter extends BeanPropertyWriter {

    public static final String CTX_AGGREGATE_TYPEID = "ddd.access.aggregateTypeId";
    public static final String CTX_PATH_STACK = "ddd.access.pathStack";

    private final long typeId;
    private final FieldDescriptor fd;
    private final AccessContextHolder holder;
    private final MetadataSnapshotProvider snapshots;

    public AccessAwareWriter(BeanPropertyWriter base, long typeId, FieldDescriptor fd,
                             AccessContextHolder holder, MetadataSnapshotProvider snapshots) {
        super(base);
        this.typeId = typeId;
        this.fd     = fd;
        this.holder = holder;
        this.snapshots = snapshots;
    }

    @Override
    public void serializeAsField(Object bean, JsonGenerator g, SerializerProvider sp)
            throws Exception {
        var ctxOpt = holder.tryGet();
        if (ctxOpt.isEmpty()) {
            // Off-request путь: используем defaultAccess
            if (!fd.defaultAccess().canRead()) {
                g.writeNullField(getName());
                return;
            }
            pushAndSerialize(bean, g, sp);
            return;
        }
        var ctx = ctxOpt.get();
        AbstractAggregate<?> instance = (bean instanceof AbstractAggregate<?> a) ? a : null;
        AccessLevel lvl = ctx.resolve(typeId, fd.fieldId(), instance);
        if (!lvl.canRead()) {
            g.writeNullField(getName());
            return;
        }
        pushAndSerialize(bean, g, sp);
    }

    @SuppressWarnings("unchecked")
    private void pushAndSerialize(Object bean, JsonGenerator g, SerializerProvider sp)
            throws Exception {
        Object savedTypeId = sp.getAttribute(CTX_AGGREGATE_TYPEID);
        Deque<Long> stack = (Deque<Long>) sp.getAttribute(CTX_PATH_STACK);
        boolean createdStack = false;
        if (stack == null) {
            stack = new ArrayDeque<>();
            sp.setAttribute(CTX_PATH_STACK, stack);
            createdStack = true;
        }
        sp.setAttribute(CTX_AGGREGATE_TYPEID, typeId);
        stack.push(fd.fieldId());
        try {
            super.serializeAsField(bean, g, sp);
        } finally {
            stack.pop();
            sp.setAttribute(CTX_AGGREGATE_TYPEID, savedTypeId);
            if (createdStack) sp.setAttribute(CTX_PATH_STACK, null);
        }
    }
}
```

### 12.5. `AccessAwareEmbeddableWriter` — стек path'ов для произвольной вложенности

Embedded-типы могут переиспользоваться в разных контейнерах И вкладываться друг в друга. `MetadataSnapshot.aggregate(typeId).fieldByPath(stack)` резолвит `FieldDescriptor` по полному path'у от корня агрегата, а не только по одиночному `parentFieldId`.

```java
public class AccessAwareEmbeddableWriter extends BeanPropertyWriter {

    private final Class<?> embeddableType;
    private final AccessContextHolder holder;
    private final MetadataSnapshotProvider snapshots;
    private final String propertyName;

    public AccessAwareEmbeddableWriter(BeanPropertyWriter base, Class<?> embeddableType,
                                        AccessContextHolder holder,
                                        MetadataSnapshotProvider snapshots) {
        super(base);
        this.embeddableType = embeddableType;
        this.holder = holder;
        this.snapshots = snapshots;
        this.propertyName = base.getName();
    }

    @Override
    @SuppressWarnings("unchecked")
    public void serializeAsField(Object bean, JsonGenerator g, SerializerProvider sp)
            throws Exception {
        Object aggTypeIdObj = sp.getAttribute(AccessAwareWriter.CTX_AGGREGATE_TYPEID);
        Deque<Long> stack = (Deque<Long>) sp.getAttribute(AccessAwareWriter.CTX_PATH_STACK);

        if (aggTypeIdObj == null || stack == null || stack.isEmpty()) {
            super.serializeAsField(bean, g, sp);    // off-context — серилизуем как есть
            return;
        }
        long aggTypeId = (Long) aggTypeIdObj;

        // Полный path от корня агрегата: например "1013.10130004.ipAddress"
        // (cookies → security → ipAddress)
        FieldDescriptor fd = snapshots.get().aggregate(aggTypeId)
                                      .fieldByPath(stack, propertyName);
        if (fd == null) {
            g.writeNullField(getName());    // не зарегистрировано — скрыть
            return;
        }
        var ctxOpt = holder.tryGet();
        if (ctxOpt.isEmpty()) {
            if (!fd.defaultAccess().canRead()) { g.writeNullField(getName()); return; }
            pushAndSerialize(bean, g, sp, fd, stack);
            return;
        }
        var ctx = ctxOpt.get();
        // Эффективный уровень: пересечение всех родителей по стеку + собственный
        AccessLevel effective = computeEffective(stack, fd, aggTypeId, ctx);
        if (!effective.canRead()) { g.writeNullField(getName()); return; }
        pushAndSerialize(bean, g, sp, fd, stack);
    }

    private void pushAndSerialize(Object bean, JsonGenerator g, SerializerProvider sp,
                                  FieldDescriptor fd, Deque<Long> stack) throws Exception {
        // Если это embedded-в-embedded, push fieldId этого поля; иначе — это leaf, не пушим
        boolean isContainer = fd.javaType().isAnnotationPresent(Embeddable.class);
        if (isContainer) stack.push(fd.fieldId());
        try {
            super.serializeAsField(bean, g, sp);
        } finally {
            if (isContainer) stack.pop();
        }
    }

    private AccessLevel computeEffective(Deque<Long> stack, FieldDescriptor fd,
                                          long aggTypeId, AccessContext ctx) {
        AggregateDescriptor agg = snapshots.get().aggregate(aggTypeId);
        AccessLevel result = AccessLevel.READ_WRITE;
        // Пересекаем уровни всех родителей по стеку
        for (Long parentFid : stack) {
            FieldDescriptor parent = agg.field(parentFid);
            if (parent == null) continue;
            result = result.intersect(ctx.resolve(aggTypeId, parent.fieldId(), null));
        }
        // И собственный уровень поля
        result = result.intersect(fd.defaultAccess());
        return result;
    }
}
```

`SerializerProvider.attributes` per-serialization-call — изолированы между concurrent serializations.

### 12.6. `AggregateReferenceSerializer`

```java
@JsonComponent
@RequiredArgsConstructor
public class AggregateReferenceSerializer
        extends JsonSerializer<AggregateReference<?, ?>> {

    private final AccessProjectionCache projectionCache;
    private final RefResolverHelper refCacheHelper;
    private final RegistryAccess registry;
    private final AccessContextHolder ctxHolder;
    private final MetadataSnapshotProvider snapshots;
    private final AccessProjectionService projectionService;

    @Override
    public void serialize(AggregateReference<?, ?> ref,
                          JsonGenerator gen, SerializerProvider sp) throws IOException {
        AccessContext ctx = ctxHolder.tryGet().orElse(null);
        if (ctx == null) {
            writeShallow(ref, gen);
            return;
        }
        gen.writeStartObject();
        gen.writeNumberField("typeId", ref.targetTypeId());
        gen.writeStringField("id", ref.targetIdRaw());

        AccessLevel repoAccess = ctx.resolveRepository(ref.targetTypeId());
        if (repoAccess.canRead()) {
            // Извлекаем уже-резолвед из RefResolutionCache (он наполнен RefBatchPrefetcher'ом
            // ЧЕРЕЗ filter-aware path — см. §14)
            var refCache = refCacheHelper.effectiveCache();
            Object loaded = refCache.tryGet(ref.targetTypeId(), ref.targetIdRaw());

            if (loaded == null) {
                // Не было prefetch'а или filter скрыл — рендерим shallow без value
                gen.writeEndObject();
                return;
            }

            String projectionKey = CacheKeys.projection(ref.targetTypeId(), ref.targetIdRaw(),
                    projectionService.fingerprintFor(
                        ref.targetTypeId(), (AbstractAggregate<?>) loaded, ctx));
            JsonNode embeddedView = projectionCache.getOrLoad(
                ref.targetTypeId(), ref.targetIdRaw(), projectionKey,
                () -> projectionService.renderView((AbstractAggregate<?>) loaded, ctx)
            );
            if (embeddedView != null) {
                gen.writeFieldName("value");
                gen.writeTree(embeddedView);
            }
        }
        gen.writeEndObject();
    }

    private void writeShallow(AggregateReference<?, ?> ref, JsonGenerator g) throws IOException {
        g.writeStartObject();
        g.writeNumberField("typeId", ref.targetTypeId());
        g.writeStringField("id", ref.targetIdRaw());
        g.writeEndObject();
    }
}
```

### 12.7. `AggregateReferenceDeserializer`

```java
@JsonComponent
public class AggregateReferenceDeserializer
        extends JsonDeserializer<AggregateReference<?, ?>> {

    @Override
    public AggregateReference<?, ?> deserialize(JsonParser p, DeserializationContext c)
            throws IOException {
        JsonNode n = p.readValueAsTree();
        long typeId = n.get("typeId").asLong();
        String id   = n.get("id").asText();
        return AggregateReference.ofRaw(typeId, id);  // value игнорируется намеренно
    }
}
```

`value` поле игнорируется — write-операция не должна изменять связанный агрегат через тело запроса.

### 12.8. `RefResolutionCache` + helper

```java
@Component
@RequestScope
public class RefResolutionCache {
    private final Map<String, Object> byKey = new HashMap<>();

    public Object tryGet(long typeId, String idRaw) {
        return byKey.get(typeId + ":" + idRaw);
    }

    @SuppressWarnings("unchecked")
    public <T> T resolve(long typeId, String idRaw, Supplier<T> loader) {
        return (T) byKey.computeIfAbsent(typeId + ":" + idRaw, k -> loader.get());
    }

    public <T> void put(long typeId, String idRaw, T value) {
        byKey.put(typeId + ":" + idRaw, value);
    }
}

public final class NoOpRefResolutionCache extends RefResolutionCache {
    public static final NoOpRefResolutionCache INSTANCE = new NoOpRefResolutionCache();
    @Override public Object tryGet(long typeId, String idRaw) { return null; }
    @Override public <T> T resolve(long typeId, String idRaw, Supplier<T> loader) { return loader.get(); }
    @Override public <T> void put(long typeId, String idRaw, T value) { /* no-op */ }
}

@Component
public class RefResolverHelper {
    private final ObjectProvider<RefResolutionCache> cacheProvider;

    public RefResolverHelper(ObjectProvider<RefResolutionCache> p) { this.cacheProvider = p; }

    public RefResolutionCache effectiveCache() {
        try { return cacheProvider.getObject(); }
        catch (BeansException e) { return NoOpRefResolutionCache.INSTANCE; }
    }
}
```

---
## 13. Репозитории, RepositoryRegistry, AccessFilterActivator

### 13.1. Базовый интерфейс

```java
@NoRepositoryBean
@AggregateRepository
public interface AggregateRepository<T extends AbstractAggregate<ID>, ID extends Serializable>
        extends JpaRepository<T, ID>, JpaSpecificationExecutor<T> {

    Optional<T> findByIdLocked(ID id);
}
```

```java
@TypeId(value = 1001, defaultRepoAccess = DefaultAccess.READ_ONLY)
public interface CustomerRepository
        extends AggregateRepository<CustomerAggregate, UUID> {
    Page<CustomerAggregate> findByEmailContaining(String q, Pageable p);
}
```

Обратите внимание: `@PostLoadAccessCheck` на интерфейсе **не нужен** — он подразумевается для всех `@AccessFiltered`-агрегатов автоматически (см. §13.7 и `app.ddd.access.implicit-post-load-check`).

### 13.2. `RepositoryRegistry`

```java
@Component
public class RepositoryRegistry {

    private final Map<Long, AggregateRepository<?, ?>> byType = new HashMap<>();
    private final Map<Class<?>, Long> typeByRepoIface = new HashMap<>();
    private final Map<Long, Class<?>> repoIfaceByTypeId = new HashMap<>();

    public RepositoryRegistry(ApplicationContext ctx) {
        Repositories repositories = new Repositories(ctx);
        for (Class<?> domainType : repositories) {
            Object repo = repositories.getRepositoryFor(domainType).orElseThrow();
            for (Class<?> iface : ClassUtils.getAllInterfacesForClass(repo.getClass())) {
                TypeId t = iface.getAnnotation(TypeId.class);
                if (t != null && AggregateRepository.class.isAssignableFrom(iface)) {
                    byType.put(t.value(), (AggregateRepository<?, ?>) repo);
                    typeByRepoIface.put(iface, t.value());
                    repoIfaceByTypeId.put(t.value(), iface);
                }
            }
        }
    }

    public AggregateRepository<?, ?> byTypeId(long typeId) {
        var r = byType.get(typeId);
        if (r == null) throw new IllegalStateException("No repository for typeId=" + typeId);
        return r;
    }

    public long typeIdOf(Class<?> repoIface) { return typeByRepoIface.get(repoIface); }

    public Class<?> repoInterfaceFor(Object proxy) {
        for (Class<?> i : ClassUtils.getAllInterfacesForClass(proxy.getClass())) {
            if (typeByRepoIface.containsKey(i)) return i;
        }
        throw new IllegalStateException("Not an aggregate repository: " + proxy);
    }

    public Class<?> repoInterfaceByTypeId(long typeId) { return repoIfaceByTypeId.get(typeId); }
    public Map<Long, AggregateRepository<?, ?>> asMap() { return Map.copyOf(byType); }
}
```

### 13.3. Hibernate `@Filter` с типизацией параметров

```java
@Entity
@Table(name = "order_aggregate")
@TypeId(2001)
@AccessFiltered(filterField = "organization", userClaim = UserClaim.ORG_IDS,
                referencedTypeId = 1002, bypassPolicy = BypassPolicy.AUTO_TRANSITIVE)
@FilterDef(name = "filter_order_organization",
           parameters = @ParamDef(name = "orgIds", type = UUID.class))
@Filter(name = "filter_order_organization",
        condition = "org_id in (:orgIds)")
public class OrderAggregate extends AbstractAggregate<UUID> { ... }
```

Параметр `:orgIds` — `UUID[]` (не `String[]`), потому что колонка `org_id UUID`. Postgres использует индекс по `(org_type_id, org_id)`.

`MetadataBootstrapper` сверяет, что `@ParamDef.type` совпадает с `idClassByTypeId(referencedTypeId)`. Несовпадение → fail-fast.

**Дополнительно** — bootstrap-валидация имени `filterField` по regex `[a-zA-Z_][a-zA-Z0-9_]*`. Это исключает SQL-injection поверхность в `FilteredCountQuery` (см. §22.2), который строит native SQL с использованием `filterColumn` (производное от `filterField`).

### 13.4. `AccessFilterActivator` — annotation-based pointcut

```java
@Aspect
@Component
@RequiredArgsConstructor
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
public class AccessFilterActivator {

    private final EntityManager em;
    private final ClaimsExtractor claimsExtractor;
    private final UserAccessProvider userAccess;
    private final MetadataSnapshotProvider snapshots;
    private final RepositoryRegistry repos;
    private final AccessContextHolder holder;
    private final MeterRegistry meter;

    @Pointcut("this(com.example.core.ddd.AggregateRepository)")
    public void anyAggregateRepoBean() {}

    @Pointcut("execution(* find*(..)) || execution(* count(..)) || execution(* count*(..)) || " +
              "execution(* exists*(..)) || execution(* search*(..))")
    public void readMethod() {}

    @Around("anyAggregateRepoBean() && readMethod()")
    public Object enableFilters(ProceedingJoinPoint pjp) throws Throwable {
        Class<?> repoIface = repos.repoInterfaceFor(pjp.getTarget());
        long typeId = repos.typeIdOf(repoIface);
        var filters = snapshots.get().filtersForTypeId(typeId);
        if (filters.isEmpty()) return pjp.proceed();    // не AccessFiltered

        var ctxOpt = holder.tryGet();
        if (ctxOpt.isEmpty()) {
            return runWithDenyAll(pjp, filters);
        }
        var ctx = ctxOpt.get();
        AccessMetric um = userAccess.metricFor(ctx);

        Session session = em.unwrap(Session.class);
        List<String> activated = new ArrayList<>();
        try {
            for (AccessFilterDef f : filters) {
                if (canBypass(um, f)) {
                    String userClass = classifyUser(um);
                    meter.counter("ddd.filter.bypassed",
                        "repoTypeId", String.valueOf(typeId),
                        "filterRefTypeId", String.valueOf(f.referencedTypeId()),
                        "userClass", userClass
                    ).increment();
                    continue;
                }

                Class<?> paramType = snapshots.get().idClassByTypeId(f.referencedTypeId());
                Set<String> rawValues = claimsExtractor.extract(ctx.auth(), f.userClaim().jwtName);
                List<Object> typedValues = rawValues.isEmpty()
                    ? List.of(denyAllSentinelFor(paramType))
                    : rawValues.stream()
                        .map(s -> IdCodec.decode(s, (Class<? extends Serializable>) paramType))
                        .collect(Collectors.toList());

                session.enableFilter(f.filterName())
                       .setParameterList(f.userClaim().jwtName, typedValues);
                activated.add(f.filterName());
            }
            return pjp.proceed();
        } finally {
            for (String name : activated) session.disableFilter(name);
        }
    }

    private Object runWithDenyAll(ProceedingJoinPoint pjp, List<AccessFilterDef> filters) throws Throwable {
        Session session = em.unwrap(Session.class);
        List<String> activated = new ArrayList<>();
        try {
            for (AccessFilterDef f : filters) {
                Class<?> paramType = snapshots.get().idClassByTypeId(f.referencedTypeId());
                session.enableFilter(f.filterName())
                       .setParameterList(f.userClaim().jwtName, List.of(denyAllSentinelFor(paramType)));
                activated.add(f.filterName());
            }
            return pjp.proceed();
        } finally {
            for (String name : activated) session.disableFilter(name);
        }
    }

    private boolean canBypass(AccessMetric um, AccessFilterDef f) {
        int g = AccessFlags.expand(um.globalFlags());
        int bypassMask = AccessFlags.ADMIN_READ | AccessFlags.ADMIN_WRITE
                       | AccessFlags.ROOT_READ  | AccessFlags.ROOT_WRITE;
        if ((g & bypassMask) != 0) return true;
        int t = AccessFlags.expand(um.typeFlags().getOrDefault(f.referencedTypeId(), 0));
        if ((t & bypassMask) != 0) return true;
        for (long tid : f.transitiveBypassTypeIds()) {
            int tf = AccessFlags.expand(um.typeFlags().getOrDefault(tid, 0));
            if ((tf & bypassMask) != 0) return true;
        }
        return false;
    }

    private String classifyUser(AccessMetric um) {
        int g = AccessFlags.expand(um.globalFlags());
        if ((g & AccessFlags.ROOT_WRITE) != 0)  return "root_write";
        if ((g & AccessFlags.ROOT_READ)  != 0)  return "root_read";
        if ((g & AccessFlags.ADMIN_WRITE) != 0) return "admin_write";
        if ((g & AccessFlags.ADMIN_READ)  != 0) return "admin_read";
        return "type_specific";
    }

    public static Object denyAllSentinelFor(Class<?> paramType) {
        if (paramType == UUID.class)    return new UUID(0xDEADBEEFCAFEBABEL, 0xDEADBEEFCAFEBABEL);
        if (paramType == Long.class)    return -1L;
        if (paramType == Integer.class) return -1;
        if (paramType == String.class)  return "__DENY_ALL_SENTINEL__";
        throw new IllegalStateException("Unsupported deny-all sentinel for " + paramType);
    }
}
```

### 13.5. Ограничения Hibernate Filter

- **Hibernate L2 cache не уважает `@Filter`** — отключён глобально (`hibernate.cache.use_second_level_cache=false`).
- **`OpenSessionInView=false`** — обязательно.
- **Envers-таблицы** не получают `@Filter`. Доступ — **только через `AuditFacade`**.
- **`@Filter` не действует на JOIN'ы из JPQL/Criteria.** Митигация — на разработчике. ArchUnit warning для `JOIN FETCH x.<accessFilteredField>`.
- **`@Filter` не действует на `EntityManager.find` / `JpaRepository.findById` / L1-cache hit.** Защита — подразумеваемая `@PostLoadAccessCheck` для filtered-типов.
- **`@Filter` для count-query — частично работает** в Hibernate ≤6.6. Для надёжности `totalElements` используется **native count query** (см. §22.2).

### 13.6. Кеширование на уровне сервиса

Для `@AccessFiltered`-агрегатов raw-кеш `aggregate:*` **не используется** — это критично: pre-filter cache hit от пользователя без права видеть строку утечёт данные.

```java
@Service
@RequiredArgsConstructor
public class CustomerService {                       // CustomerAggregate имеет @AccessFiltered

    private final CustomerRepository repo;
    private final AccessContextHolder holder;
    private final AccessProjectionService projections;
    private final Validator validator;

    public Optional<CustomerView> findView(UUID id) {
        var ctx = holder.getOrThrow();
        return repo.findById(id)
                   .map(a -> projections.renderViewAsObject(a, ctx, CustomerView.class));
    }
}

@Service
@RequiredArgsConstructor
public class ConfigService {                         // ConfigAggregate БЕЗ @AccessFiltered

    private final ConfigRepository repo;

    @Cacheable(cacheNames = "aggregate", key = "'config:' + #key")
    public Optional<ConfigAggregate> findRaw(String key) {
        return repo.findByKey(key);
    }
}
```

`AggregateCacheGuard` (`SmartInitializingSingleton` с `@Order(HIGHEST_PRECEDENCE + 1000)`) сканирует все `@Cacheable("aggregate")`-методы и фейлится с понятной ошибкой, если возвращаемый тип — `@AccessFiltered`-агрегат.

### 13.7. `PostLoadAccessCheckListener` — два режима: `THROW` и `MARK_AND_DROP`

`MetadataBootstrapper` автоматически регистрирует listener для **всех** `@AccessFiltered`-типов (если `app.ddd.access.implicit-post-load-check=true`, default). Явный opt-out — `@PostLoadAccessCheck(false)` на интерфейсе репозитория, требующий комментарий-обоснование.

**Два режима поведения** (G4):

| Режим | Когда применяется | Что делает |
|---|---|---|
| `THROW` | `findById`, `findByIdLocked`, `getOne` (single-row API) | Бросает `AccessDeniedException` — single-row UX: либо строка доступна, либо запрос упал. |
| `MARK_AND_DROP` | `findAll`, `Page<T>`, `findAllById`, `Specification`-queries (collection API) | Помечает entity через `PostLoadDropMarker` (request-scoped); `AccessAwarePage`/`wrapPage`/итераторы фильтруют помеченные после load'а. Не бросает — на 1 запрещённую строку не падает вся страница. |

Режим устанавливается AOP-аспектом `AccessFilterActivator` через `ThreadLocal<PostLoadMode>` перед делегированием к target-методу:

```java
public enum PostLoadMode { THROW, MARK_AND_DROP }

public final class PostLoadModeHolder {
    private static final ThreadLocal<PostLoadMode> CURRENT = ThreadLocal.withInitial(() -> PostLoadMode.THROW);

    public static PostLoadMode current() { return CURRENT.get(); }

    public static AutoCloseable bind(PostLoadMode mode) {
        PostLoadMode prev = CURRENT.get();
        CURRENT.set(mode);
        return () -> CURRENT.set(prev);
    }
}

/** Request-scoped пометка «entity отброшена post-load access-check'ом». */
@Component
@RequestScope
public class PostLoadDropMarker {
    private final IdentityHashMap<Object, Boolean> dropped = new IdentityHashMap<>();
    private final AtomicInteger droppedCount = new AtomicInteger();

    public void mark(Object entity)    { dropped.put(entity, Boolean.TRUE); droppedCount.incrementAndGet(); }
    public boolean isMarked(Object e)  { return Boolean.TRUE.equals(dropped.get(e)); }
    public int droppedCount()          { return droppedCount.get(); }
    public boolean hadAnyDrops()       { return droppedCount.get() > 0; }
}
```

`AccessFilterActivator.enableFilters` дополняется установкой режима по имени метода:

```java
@Around("anyAggregateRepoBean() && readMethod()")
public Object enableFilters(ProceedingJoinPoint pjp) throws Throwable {
    Class<?> repoIface = repos.repoInterfaceFor(pjp.getTarget());
    long typeId = repos.typeIdOf(repoIface);
    var filters = snapshots.get().filtersForTypeId(typeId);

    PostLoadMode mode = inferModeFromMethod(pjp.getSignature().getName());
    try (var ignored = PostLoadModeHolder.bind(mode)) {
        if (filters.isEmpty()) return pjp.proceed();    // не AccessFiltered
        var ctxOpt = holder.tryGet();
        if (ctxOpt.isEmpty()) {
            return runWithDenyAll(pjp, filters);
        }
        // ... остальная логика без изменений (см. §13.4)
    }
}

private static PostLoadMode inferModeFromMethod(String methodName) {
    // Single-row контракты: findById, findByIdLocked, getById, existsById, getOne, getReferenceById
    if (methodName.equals("findById") || methodName.equals("findByIdLocked")
        || methodName.equals("getById") || methodName.equals("existsById")
        || methodName.equals("getOne")  || methodName.equals("getReferenceById")) {
        return PostLoadMode.THROW;
    }
    // Всё остальное (findAll, findAllById, count, search...) — MARK_AND_DROP
    return PostLoadMode.MARK_AND_DROP;
}
```

Сам listener:

```java
@Component
@RequiredArgsConstructor
public class PostLoadAccessCheckListener implements PostLoadEventListener {

    private final MetadataSnapshotProvider snapshots;
    private final AccessContextHolder holder;
    private final UserAccessProvider userAccess;
    private final ClaimsExtractor claimsExtractor;
    private final ObjectProvider<PostLoadDropMarker> dropMarkerProvider;
    private final MeterRegistry meter;

    private final Set<Long> activeTypeIds = ConcurrentHashMap.newKeySet();

    public void registerForTypeId(long typeId)   { activeTypeIds.add(typeId); }
    public void unregisterForTypeId(long typeId) { activeTypeIds.remove(typeId); }

    @Override
    public void onPostLoad(PostLoadEvent ev) {
        if (!(ev.getEntity() instanceof AbstractAggregate<?> agg)) return;
        long typeId = snapshots.get().typeIdOf(agg.getClass());
        if (!activeTypeIds.contains(typeId)) return;

        var ctxOpt = holder.tryGet();
        if (ctxOpt.isEmpty()) {
            // Без AccessContext — единственный безопасный путь: бросить.
            throw new AccessDeniedException(
                "Load of " + agg.getClass().getSimpleName() + "#" + agg.getId() +
                " requires AccessContext (@PostLoadAccessCheck active)");
        }
        var ctx = ctxOpt.get();
        // bootstrap-контекст для UserAggregate тоже создаёт bypass
        if (ctx.isBootstrapForType(typeId)) return;

        AccessMetric um = userAccess.metricFor(ctx);
        var snap = snapshots.get();
        var filters = snap.filtersForTypeId(typeId);

        for (AccessFilterDef f : filters) {
            if (canBypass(um, f)) continue;

            FieldDescriptor fd = snap.aggregate(typeId).fieldByName(f.filterField());
            Object refValue = fd.read(agg);
            if (!(refValue instanceof AggregateReference<?, ?> ref)) continue;
            String refIdRaw = ref.targetIdRaw();

            Set<String> claimValues = claimsExtractor.extract(ctx.auth(), f.userClaim().jwtName);
            if (!claimValues.contains(refIdRaw)) {
                handleDenial(typeId, f.filterField(), agg, refIdRaw);
                return;     // одна причина отказа достаточна
            }
        }
    }

    private void handleDenial(long typeId, String filterField, AbstractAggregate<?> agg, String refIdRaw) {
        meter.counter("ddd.postload.access_denied",
            "typeId", String.valueOf(typeId),
            "filterField", filterField).increment();

        switch (PostLoadModeHolder.current()) {
            case THROW -> throw new AccessDeniedException(
                "Cross-tenant load detected: " + agg.getClass().getSimpleName() +
                "#" + agg.getId() + " " + filterField + "=" + refIdRaw);
            case MARK_AND_DROP -> {
                meter.counter("ddd.postload.dropped",
                    "typeId", String.valueOf(typeId)).increment();
                PostLoadDropMarker marker = dropMarkerProvider.getIfAvailable();
                if (marker != null) {
                    marker.mark(agg);
                } else {
                    // Нет request-scope (off-thread / non-HTTP): fail-safe — кидаем
                    throw new AccessDeniedException(
                        "MARK_AND_DROP requested off-thread: " + agg.getClass().getSimpleName() +
                        "#" + agg.getId());
                }
            }
        }
    }

    private boolean canBypass(AccessMetric um, AccessFilterDef f) {
        int bypassMask = AccessFlags.ADMIN_READ | AccessFlags.ADMIN_WRITE
                       | AccessFlags.ROOT_READ  | AccessFlags.ROOT_WRITE;
        int g = AccessFlags.expand(um.globalFlags());
        if ((g & bypassMask) != 0) return true;
        int t = AccessFlags.expand(um.typeFlags().getOrDefault(f.referencedTypeId(), 0));
        if ((t & bypassMask) != 0) return true;
        for (long tid : f.transitiveBypassTypeIds()) {
            int tf = AccessFlags.expand(um.typeFlags().getOrDefault(tid, 0));
            if ((tf & bypassMask) != 0) return true;
        }
        return false;
    }
}
```

> **Важно:** в `MARK_AND_DROP` listener **не модифицирует** managed-сущность (не nullит поля, не выбрасывает из persistence-context'а). Помеченные entity всё ещё managed, но **отфильтрованы** на уровне ответа `Page`/итератора. Это позволяет dirty-check'ам Hibernate работать корректно (если кто-то по ошибке мутирует помеченную entity, Hibernate всё равно отследит изменение — поэтому в `_marked_`-set'е работают только READ-сценарии, write через managed-entity заблокируется `AccessAwarePreUpdateListener`'ом обычным путём).

`PostLoadEventListener` регистрируется через `IntegratorProvider` (см. §15.2):

```java
// Bootstrap-логика регистрации typeId'ов:
for (var desc : aggregates.values()) {
    boolean hasFilters = !filters.getOrDefault(desc.typeId(), List.of()).isEmpty();
    if (!hasFilters) continue;
    Class<?> repoIface = repos.repoInterfaceByTypeId(desc.typeId());
    PostLoadAccessCheck explicit = repoIface == null ? null
            : repoIface.getAnnotation(PostLoadAccessCheck.class);
    boolean enabled;
    if (explicit != null) {
        enabled = explicit.value();         // явное value=true/false
    } else {
        enabled = props.getAccess().isImplicitPostLoadCheck();   // глобальный toggle, default true
    }
    if (enabled) listener.registerForTypeId(desc.typeId());
}
```

ArchUnit-правило: `@PostLoadAccessCheck(false)` обязан быть в classpath с комментарием Javadoc:

```java
@ArchTest
static final ArchRule explicit_disable_must_be_documented =
    classes().that().areAnnotatedWith(PostLoadAccessCheck.class)
             .and(classWhereAnnotationValueIsFalse(PostLoadAccessCheck.class))
             .should(haveJavadocContaining("PostLoadAccessCheck disabled because"));
```

---

## 14. RefBatchPrefetcher — filter-aware и configurable depth

### 14.1. Архитектура

`RefBatchPrefetcher` устраняет N+1 при сериализации страницы с `AggregateReference`-полями. **Критическое свойство:** prefetch для `@AccessFiltered`-типов проходит **через AOP-обёрнутый репозиторий** с активированным Hibernate Filter. Это гарантирует, что фильтр НЕ обходится при batch-loading. Глубина обхода — конфигурируемая через `@PrefetchDepth` per-aggregate (default 3).

```java
@Component
@RequiredArgsConstructor
public class RefBatchPrefetcher {

    private final RegistryAccess registry;
    private final RefResolverHelper cacheHelper;
    private final MetadataSnapshotProvider snapshots;
    private final MeterRegistry meter;
    private final BootstrapProperties props;

    public void prefetch(Object root) {
        var cache = cacheHelper.effectiveCache();
        Map<Long, Set<String>> byType = new HashMap<>();
        IdentityHashMap<Object, Boolean> visited = new IdentityHashMap<>();
        collectRefs(root, byType, visited, 0, depthForRoot(root));

        for (var entry : byType.entrySet()) {
            long typeId = entry.getKey();
            Set<String> idsRaw = entry.getValue();
            if (idsRaw.isEmpty()) continue;

            Class<?> idClass = registry.idClassByTypeId(typeId);
            List<? extends Serializable> ids = idsRaw.stream()
                    .map(s -> IdCodec.decode(s, (Class<? extends Serializable>) idClass))
                    .toList();

            // КРИТИЧНО: вызываем findAllById ЧЕРЕЗ proxy — AccessFilterActivator активирует
            // фильтр (для @AccessFiltered-типов) или пропустит (для не-filtered).
            // findAllById попадает под pointcut `find*` — фильтр сработает.
            @SuppressWarnings({"rawtypes", "unchecked"})
            AggregateRepository repo = registry.repositoryByTypeId(typeId);
            @SuppressWarnings({"rawtypes", "unchecked"})
            List<? extends AbstractAggregate<?>> loaded =
                ((AggregateRepository) repo).findAllById((List) ids);

            // Те, что отфильтровались — отсутствуют в loaded → AggregateReferenceSerializer
            // не найдёт их в RefResolutionCache.tryGet → отрендерит shallow {typeId, id}.
            for (var agg : loaded) {
                cache.put(typeId, IdCodec.encode(agg.getId()), agg);
            }
        }
    }

    private int depthForRoot(Object root) {
        Class<?> rootCls = unwrapRootClass(root);
        if (rootCls == null) return props.getRefprefetch().getDefaultDepth();
        long typeId;
        try { typeId = snapshots.get().typeIdOf(rootCls); }
        catch (Exception e) { return props.getRefprefetch().getDefaultDepth(); }
        var desc = snapshots.get().aggregate(typeId);
        return desc.prefetchDepth();
    }

    private Class<?> unwrapRootClass(Object root) {
        if (root instanceof AbstractAggregate<?> a) return a.getClass();
        if (root instanceof Page<?> p && !p.getContent().isEmpty())
            return p.getContent().get(0).getClass();
        if (root instanceof Iterable<?> it) {
            for (var e : it) if (e instanceof AbstractAggregate<?> a) return a.getClass();
        }
        return null;
    }

    private void collectRefs(Object node, Map<Long, Set<String>> acc,
                             IdentityHashMap<Object, Boolean> visited,
                             int depth, int maxDepth) {
        if (node == null || visited.containsKey(node)) return;
        if (depth > maxDepth) {
            // Усечение: выдаём метрику, но молча пропускаем
            if (node instanceof AbstractAggregate<?> a) {
                long tid = snapshots.get().typeIdOf(a.getClass());
                meter.counter("ddd.refprefetch.truncated", "typeId", String.valueOf(tid)).increment();
            }
            return;
        }
        visited.put(node, true);

        if (node instanceof AggregateReference<?,?> r) {
            acc.computeIfAbsent(r.targetTypeId(), k -> new HashSet<>()).add(r.targetIdRaw());
            return;
        }
        if (node instanceof Iterable<?> it) {
            for (var x : it) collectRefs(x, acc, visited, depth + 1, maxDepth);
            return;
        }
        if (node instanceof Page<?> p) {
            for (var x : p.getContent()) collectRefs(x, acc, visited, depth + 1, maxDepth);
            return;
        }
        if (node instanceof AbstractAggregate<?> a) {
            long typeId = snapshots.get().typeIdOf(a.getClass());
            for (var fd : snapshots.get().aggregate(typeId).fields()) {
                Object v = fd.read(a);
                if (v != null) collectRefs(v, acc, visited, depth + 1, maxDepth);
            }
        }
    }
}
```

### 14.2. ArchUnit-правило: запрет прямого `findAllById` на filtered-репо

```java
@ArchTest
static final ArchRule findAllById_only_via_prefetcher_or_with_active_context =
    methods().that().haveName("findAllById")
             .and().areDeclaredInClassesThat().areAssignableTo(AggregateRepository.class)
             .should(beCalledOnlyFrom(
                 "com.example.core.persistence.RefBatchPrefetcher",
                 "com.example.core.audit.AuditFacade",
                 "com.example..*Test",
                 "com.example..*IT"
             ));
```

Любое прямое `repo.findAllById(ids)` в бизнес-коде требует явного allow-list'а.

### 14.3. Поведение для разных типов агрегатов

| Целевой тип | Поведение prefetch'а |
|---|---|
| Не-`@AccessFiltered` | `findAllById` без фильтра, всё попадает в cache |
| `@AccessFiltered` без admin/root у пользователя | `findAllById` **с активированным** Hibernate Filter; в cache попадают только entity, видимые пользователю |
| `@AccessFiltered` с admin/root у пользователя | `AccessFilterActivator.canBypass` пропускает фильтр; всё попадает в cache |
| `AccessContext` отсутствует | `AccessFilterActivator.runWithDenyAll` → пустой результат → cache пустой → серилизатор рендерит shallow для всех refs |

---

## 15. Bean Validation (требование 1)

### 15.1. Двухслойная валидация

JPA-уровневая Bean Validation **выключена** глобально:

```yaml
spring.jpa.properties.jakarta.persistence.validation.mode: none
```

В тестовом профиле — `callback`.

Все вызовы Bean Validation на production — **только** явные:

1. **HTTP-контроллер** — `@Valid` на `@RequestBody` (структурная валидация DTO).
2. **Сервисный слой** — `Validator.validate(target)` после `mapper.mergeInto(...)` и перед `repo.save(...)` (валидация итогового состояния агрегата).

> **Enforcement через интеграционные тесты, не ArchUnit.** Контракт «`Validator.validate` вызывается перед `repo.save`» проверяется `@SpringBootTest`-сценариями с SpyBean'ом валидатора (см. §3.3).

**Слой A — структурная** (`jakarta.validation`):

```java
@NotBlank @Size(max = 255) String fullName;
@Email                     String email;
@Future                    LocalDate expiresAt;
@Valid                     List<CookieRecord> cookies;
@ValidAggregateRef(target = OrganizationAggregate.class, idType = UUID.class)
                           AggregateReference<OrganizationAggregate, UUID> organization;
```

**Слой B — access-aware** через Hibernate event listeners (`AccessAwarePreUpdateListener`/`AccessAwarePreInsertListener`).

### 15.2. Регистрация listener'ов через `IntegratorProvider`

Hibernate event-listener'ы должны быть зарегистрированы в `EventListenerRegistry` **до** создания `EntityManagerFactory`. В Spring Boot 3.x официальный механизм для этого — свойство `hibernate.integrator_provider`, принимающее `org.hibernate.jpa.boot.spi.IntegratorProvider`. Свойство пробрасывается через `HibernatePropertiesCustomizer`:

> **Почему не `hibernate.session_factory.statement_inspector`:** этот ключ принимает `StatementInspector`, не `Integrator`, и для регистрации listener'ов **не работает** — listener'ы не попадут в registry. Контракт регистрации обязательно проверяется интеграционным тестом `AccessListenerRegistrationIT` (см. конец раздела).

```java
@Configuration
@RequiredArgsConstructor
public class AccessAwareHibernateConfig {

    private final ObjectProvider<AccessAwarePreUpdateListener> updateListenerProvider;
    private final ObjectProvider<AccessAwarePreInsertListener> insertListenerProvider;
    private final ObjectProvider<PostLoadAccessCheckListener>  postLoadListenerProvider;

    @Bean
    public AccessAwarePreUpdateListener accessAwarePreUpdateListener(
            ObjectProvider<AccessResolver> r,
            ObjectProvider<MetadataSnapshotProvider> s,
            ObjectProvider<AccessContextHolder> h) {
        return new AccessAwarePreUpdateListener(r, s, h);
    }

    @Bean
    public AccessAwarePreInsertListener accessAwarePreInsertListener(
            ObjectProvider<AccessResolver> r,
            ObjectProvider<MetadataSnapshotProvider> s,
            ObjectProvider<AccessContextHolder> h) {
        return new AccessAwarePreInsertListener(r, s, h);
    }

    @Bean
    public PostLoadAccessCheckListener postLoadAccessCheckListener(
            ObjectProvider<MetadataSnapshotProvider> s,
            ObjectProvider<AccessContextHolder> h,
            ObjectProvider<UserAccessProvider> u,
            ObjectProvider<ClaimsExtractor> c,
            ObjectProvider<PostLoadDropMarker> d,
            MeterRegistry m) {
        return new PostLoadAccessCheckListener(s, h, u, c, d, m);
    }

    /**
     * Регистрируем IntegratorProvider через специальный ключ Spring Boot.
     * Spring Boot прочитает это свойство и применит IntegratorProvider к BootstrapServiceRegistry
     * до создания SessionFactory'и. Listener'ы попадут в EventListenerRegistry до первого SQL-запроса.
     */
    @Bean
    public HibernatePropertiesCustomizer integratorProviderCustomizer() {
        return props -> {
            IntegratorProvider provider = () -> List.of(new AccessIntegrator(
                updateListenerProvider, insertListenerProvider, postLoadListenerProvider
            ));
            props.put("hibernate.integrator_provider", provider);
        };
    }

    /** Сам Integrator — вызывается Hibernate'ом в момент создания SessionFactory. */
    @RequiredArgsConstructor
    public static class AccessIntegrator implements Integrator {

        private final ObjectProvider<AccessAwarePreUpdateListener> updateP;
        private final ObjectProvider<AccessAwarePreInsertListener> insertP;
        private final ObjectProvider<PostLoadAccessCheckListener>  postLoadP;

        @Override
        public void integrate(Metadata m, BootstrapContext bc, SessionFactoryImplementor sf) {
            EventListenerRegistry registry =
                sf.getServiceRegistry().getService(EventListenerRegistry.class);
            // ObjectProvider.getObject() резолвит bean из Spring DI.
            // К моменту integrate() Spring DI готов (это поздняя стадия bootstrap'а).
            registry.appendListeners(EventType.PRE_UPDATE, updateP.getObject());
            registry.appendListeners(EventType.PRE_INSERT, insertP.getObject());
            registry.appendListeners(EventType.POST_LOAD,  postLoadP.getObject());
        }

        @Override
        public void disintegrate(SessionFactoryImplementor sf, SessionFactoryServiceRegistry sr) {}
    }
}
```

#### Контракт-тест (обязательный)

Тест проверяет, что listener'ы **действительно** в registry. Без этого теста G1 невозможно подтвердить — баг проявится только при попытке записи на prod.

```java
@SpringBootTest
class AccessListenerRegistrationIT {

    @Autowired EntityManagerFactory emf;

    @Test
    void preUpdateListenerIsRegistered() {
        var sf = emf.unwrap(SessionFactoryImplementor.class);
        var registry = sf.getServiceRegistry().getService(EventListenerRegistry.class);

        boolean preUpdate = registry.getEventListenerGroup(EventType.PRE_UPDATE)
            .listeners().anyMatch(l -> l instanceof AccessAwarePreUpdateListener);
        boolean preInsert = registry.getEventListenerGroup(EventType.PRE_INSERT)
            .listeners().anyMatch(l -> l instanceof AccessAwarePreInsertListener);
        boolean postLoad  = registry.getEventListenerGroup(EventType.POST_LOAD)
            .listeners().anyMatch(l -> l instanceof PostLoadAccessCheckListener);

        assertThat(preUpdate).as("AccessAwarePreUpdateListener registered").isTrue();
        assertThat(preInsert).as("AccessAwarePreInsertListener registered").isTrue();
        assertThat(postLoad) .as("PostLoadAccessCheckListener registered") .isTrue();
    }
}
```

### 15.3. `AccessAwarePreUpdateListener`

```java
@RequiredArgsConstructor
public class AccessAwarePreUpdateListener implements PreUpdateEventListener {

    private final ObjectProvider<AccessResolver> resolver;
    private final ObjectProvider<MetadataSnapshotProvider> snapshots;
    private final ObjectProvider<AccessContextHolder> holder;

    @Override
    public boolean onPreUpdate(PreUpdateEvent ev) {
        if (!(ev.getEntity() instanceof AbstractAggregate<?> agg)) return false;
        AccessChecked ann = agg.getClass().getAnnotation(AccessChecked.class);
        boolean strict = ann == null || ann.strict();

        var ctx = holder.getObject().tryGet().orElse(null);
        if (ctx == null) {
            if (strict) throw new AccessDeniedException(
                "Write to " + agg.getClass().getSimpleName() +
                " requires AccessContext (declared @AccessChecked(strict=true))");
            return false;
        }

        if (ctx.isSystemMaxPrivileged()) return false;

        var snap = snapshots.getObject().get();
        long typeId = snap.typeIdOf(agg.getClass());
        AggregateDescriptor desc = snap.aggregate(typeId);

        Object[] oldState = ev.getOldState();
        Object[] newState = ev.getState();
        String[] propNames = ev.getPersister().getPropertyNames();

        for (int i = 0; i < propNames.length; i++) {
            FieldDescriptor fd = desc.fieldByPropertyName(propNames[i]);
            if (fd == null) continue;
            Object oldVal = oldState[i];
            Object newVal = newState[i];
            if (Objects.equals(oldVal, newVal)) continue;   // AccessMetric.equals корректен (см. §5.1)

            AccessLevel lvl = resolver.getObject().resolve(typeId, fd.fieldId(), agg, ctx);
            if (!lvl.canWrite(oldVal)) {
                throw new AccessDeniedException(
                    "Field " + fd.name() + " (id=" + fd.fieldId() + ") not writable" +
                    (lvl.mode() == WriteMode.MODIFY_EMPTY ? " (init-once, already set)" : ""));
            }
        }
        return false;
    }
}
```

### 15.4. `AccessAwarePreInsertListener`

```java
@RequiredArgsConstructor
public class AccessAwarePreInsertListener implements PreInsertEventListener {

    private final ObjectProvider<AccessResolver> resolver;
    private final ObjectProvider<MetadataSnapshotProvider> snapshots;
    private final ObjectProvider<AccessContextHolder> holder;

    @Override
    public boolean onPreInsert(PreInsertEvent ev) {
        if (!(ev.getEntity() instanceof AbstractAggregate<?> agg)) return false;
        AccessChecked ann = agg.getClass().getAnnotation(AccessChecked.class);
        boolean strict = ann == null || ann.strict();

        var ctx = holder.getObject().tryGet().orElse(null);
        if (ctx == null) {
            if (strict) throw new AccessDeniedException(
                "Insert into " + agg.getClass().getSimpleName() + " requires AccessContext");
            return false;
        }

        if (ctx.isSystemMaxPrivileged()) return false;

        var snap = snapshots.getObject().get();
        long typeId = snap.typeIdOf(agg.getClass());
        AccessLevel repoLvl = resolver.getObject().resolveRepository(typeId, ctx);
        if (!repoLvl.canWrite(null)) {
            throw new AccessDeniedException("Repository " + typeId + " not writable");
        }

        Object[] newState = ev.getState();
        String[] propNames = ev.getPersister().getPropertyNames();
        AggregateDescriptor desc = snap.aggregate(typeId);

        for (int i = 0; i < propNames.length; i++) {
            FieldDescriptor fd = desc.fieldByPropertyName(propNames[i]);
            if (fd == null) continue;
            Object newVal = newState[i];
            if (newVal == null) continue;

            AccessLevel lvl = resolver.getObject().resolve(typeId, fd.fieldId(), agg, ctx);
            if (!lvl.canWrite(null)) {
                throw new AccessDeniedException(
                    "Field " + fd.name() + " (id=" + fd.fieldId() + ") not writable on insert");
            }
        }
        return false;
    }
}
```

---

## 16. Доменные хуки и lifecycle (требование 8)

### 16.1. `LifecyclePhase`

```java
public enum LifecyclePhase {
    CREATE,
    UPDATE,
    DELETE,
    SOFT_DELETE
}
```

### 16.2. `AggregateLifecycleListener` — JPA-listener со static-bridge к Spring

JPA `@EntityListeners` создаёт listener'ы через **default constructor**, не через Spring DI. Поэтому `final` поля у listener-класса — это `null` на момент `@PrePersist`. Решение — статический мост через `ApplicationContext`, инициализируемый Spring-managed `LifecycleProcessorBootstrap`.

```java
public class AggregateLifecycleListener {

    private static volatile ApplicationContext APP_CTX;
    private static final ThreadLocal<Boolean> IN_FLUSH = new ThreadLocal<>();

    /** Вызывается из LifecycleProcessorBootstrap (@PostConstruct). */
    public static void init(ApplicationContext ctx) { APP_CTX = ctx; }

    public static boolean inFlush() { return Boolean.TRUE.equals(IN_FLUSH.get()); }

    @PrePersist  public void prePersist(AbstractAggregate<?> a)  { runPre(a, LifecyclePhase.CREATE); }
    @PreUpdate   public void preUpdate(AbstractAggregate<?> a)   { runPre(a, LifecyclePhase.UPDATE); }
    @PreRemove   public void preRemove(AbstractAggregate<?> a)   { runPre(a, LifecyclePhase.DELETE); }

    private void runPre(AbstractAggregate<?> a, LifecyclePhase ph) {
        var ctx = APP_CTX;
        if (ctx == null) return;     // bootstrap-окно (до LifecycleProcessorBootstrap.init)
        var processor = ctx.getBean(LifecycleProcessor.class);
        IN_FLUSH.set(Boolean.TRUE);
        try {
            processor.runPre(a, ph);
        } finally {
            IN_FLUSH.remove();
        }
    }
}

@Component
@RequiredArgsConstructor
public class LifecycleProcessor {

    private final ApplicationEventPublisher publisher;
    private final CallbackDispatcher dispatcher;
    private final MetadataSnapshotProvider snapshots;

    public void runPre(AbstractAggregate<?> a, LifecyclePhase ph) {
        a.onPreFlush(ph);
        dispatcher.dispatch(a, DomainCallback.Phase.PRE_FLUSH, ph);
        registerBeforeCommit(a, ph);
        registerAfterCommit(a, ph);
    }

    private void registerBeforeCommit(AbstractAggregate<?> a, LifecyclePhase ph) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            a.onBeforeCommit(ph);
            dispatcher.dispatch(a, DomainCallback.Phase.BEFORE_COMMIT, ph);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization() {
                @Override public void beforeCommit(boolean readOnly) {
                    a.onBeforeCommit(ph);
                    dispatcher.dispatch(a, DomainCallback.Phase.BEFORE_COMMIT, ph);
                }
            });
    }

    private void registerAfterCommit(AbstractAggregate<?> a, LifecyclePhase ph) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            a.onAfterCommit(ph);
            dispatcher.dispatch(a, DomainCallback.Phase.AFTER_COMMIT, ph);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization() {
                @Override public void afterCommit() {
                    a.onAfterCommit(ph);
                    dispatcher.dispatch(a, DomainCallback.Phase.AFTER_COMMIT, ph);
                    long typeId = snapshots.get().typeIdOf(a.getClass());
                    publisher.publishEvent(new AggregateChangedEvent(
                        typeId, IdCodec.encode(a.getId()), ph));
                }
            });
    }
}

@Component
@RequiredArgsConstructor
public class LifecycleProcessorBootstrap {
    private final ApplicationContext ctx;

    @PostConstruct
    void wire() {
        AggregateLifecycleListener.init(ctx);
    }
}
```

### 16.3. In-flush guard

Запрещает БД-операции из `onPreFlush`-callback'а:

```java
@Aspect
@Component
public class InFlushDatabaseAccessGuard {

    @Around("execution(* org.springframework.data.jpa.repository.JpaRepository+.save(..)) || " +
            "execution(* jakarta.persistence.EntityManager.persist(..)) || " +
            "execution(* jakarta.persistence.EntityManager.merge(..))")
    public Object guard(ProceedingJoinPoint pjp) throws Throwable {
        if (AggregateLifecycleListener.inFlush()) {
            throw new PreFlushDatabaseAccessException(
                "Database write detected inside @PreFlush callback. This causes infinite flush " +
                "recursion. Move the operation to @AfterCommit or to a service-level method.");
        }
        return pjp.proceed();
    }
}
```

### 16.4. `@DomainCallback`

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface DomainCallback {
    long typeId();
    Phase phase();
    Kind  kind();

    /**
     * Если true (по умолчанию), при отсутствии AccessContext в потоке
     * вызов callback'а БРОСАЕТ IllegalStateException.
     */
    boolean requiresContext() default true;

    enum Phase { PRE_FLUSH, BEFORE_COMMIT, AFTER_COMMIT }
    enum Kind  { CREATE, UPDATE, DELETE, SOFT_DELETE, ANY }
}
```

```java
@Component
public class CustomerEventHandlers {

    @DomainCallback(typeId = 1001, phase = AFTER_COMMIT, kind = CREATE)
    public void onCustomerCreated(CustomerAggregate c, AccessContext ctx) {
        log.info("Welcome by {} for {}", ctx.principalRef().targetIdRaw(), c.getId());
    }

    @DomainCallback(typeId = 1001, phase = BEFORE_COMMIT, kind = UPDATE)
    public void enforceInvariantOnUpdate(CustomerAggregate c, AccessContext ctx) {
        if (c.getEmail() != null && !isUnique(c.getEmail()))
            throw new BusinessRuleViolation("Email not unique");
    }
}
```

### 16.5. `CallbackDispatcher` — резолв bean'а из контекста для каждого invoke

`CallbackDispatcher` хранит `(beanName, Method)`, **не** связанный заранее `MethodHandle.bindTo(bean)`. На каждом `invoke` bean резолвится из `ApplicationContext`, что гарантирует:
- Spring-CGLIB-прокси (`@Transactional`/`@Async`/`@Cacheable`) применяются корректно,
- `@Lazy`/`@Scope("prototype")` инициализируются по требованию.

Цена — десятки наносекунд на reflect.invoke; для callback-диспетчинга это незначительно (overhead транзакции на порядки больше).

```java
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1000)
@RequiredArgsConstructor
public class CallbackDispatcher implements SmartInitializingSingleton {

    private final ApplicationContext appCtx;
    private final MetadataSnapshotProvider snapshots;
    private final AccessContextHolder holder;

    private final Map<Long, Map<CallbackKey, List<Invocation>>> handlers = new HashMap<>();

    private record CallbackKey(DomainCallback.Phase phase, DomainCallback.Kind kind) {}
    private record Invocation(String beanName, Method method,
                              Class<?> aggregateClass, boolean acceptsContext,
                              boolean requiresContext) {}

    @Override
    public void afterSingletonsInstantiated() {
        for (var beanName : appCtx.getBeanDefinitionNames()) {
            Object bean;
            try { bean = appCtx.getBean(beanName); }
            catch (BeansException e) { continue; }
            Class<?> beanClass = AopUtils.getTargetClass(bean);
            for (Method m : beanClass.getMethods()) {
                DomainCallback ann = m.getAnnotation(DomainCallback.class);
                if (ann == null) continue;
                validateSignature(m, ann);

                long typeId = ann.typeId();
                CallbackKey key = new CallbackKey(ann.phase(), ann.kind());
                Class<?> aggClass = m.getParameterTypes()[0];
                boolean withCtx  = m.getParameterCount() == 2;

                handlers.computeIfAbsent(typeId, k -> new HashMap<>())
                        .computeIfAbsent(key, k -> new ArrayList<>())
                        .add(new Invocation(beanName, m, aggClass, withCtx, ann.requiresContext()));
            }
        }
    }

    private void validateSignature(Method m, DomainCallback ann) {
        Class<?>[] params = m.getParameterTypes();
        if (params.length < 1 || params.length > 2)
            throw new IllegalStateException("@DomainCallback must have 1 or 2 params: " + m);
        if (!AbstractAggregate.class.isAssignableFrom(params[0]))
            throw new IllegalStateException("First param must be AbstractAggregate: " + m);
        if (params.length == 2 && params[1] != AccessContext.class)
            throw new IllegalStateException("Second param must be AccessContext: " + m);
        long actualTypeId = snapshots.get().typeIdOf(params[0]);
        if (actualTypeId != ann.typeId())
            throw new IllegalStateException(
                "@DomainCallback typeId=" + ann.typeId() +
                " does not match aggregate class typeId=" + actualTypeId);
    }

    public void dispatch(AbstractAggregate<?> agg, DomainCallback.Phase phase,
                         LifecyclePhase lifecyclePhase) {
        long typeId = snapshots.get().typeIdOf(agg.getClass());
        var byKey = handlers.get(typeId);
        if (byKey == null) return;

        DomainCallback.Kind kind = mapKind(lifecyclePhase);
        invokeBucket(agg, byKey.get(new CallbackKey(phase, kind)), phase);
        invokeBucket(agg, byKey.get(new CallbackKey(phase, DomainCallback.Kind.ANY)), phase);
    }

    private void invokeBucket(AbstractAggregate<?> agg, List<Invocation> invocations,
                              DomainCallback.Phase phase) {
        if (invocations == null) return;
        Optional<AccessContext> ctxOpt = holder.tryGet();
        for (var inv : invocations) {
            AccessContext ctx;
            if (ctxOpt.isPresent()) {
                ctx = ctxOpt.get();
            } else if (!inv.requiresContext) {
                ctx = null;
            } else {
                throw new IllegalStateException(
                    "@DomainCallback for " + agg.getClass().getSimpleName() +
                    " phase=" + phase +
                    " requires AccessContext but none is bound on this thread.");
            }
            try {
                // Резолв bean'а на каждом invoke — Spring-прокси работают корректно
                Object bean = appCtx.getBean(inv.beanName);
                if (inv.acceptsContext) inv.method.invoke(bean, agg, ctx);
                else                    inv.method.invoke(bean, agg);
            } catch (InvocationTargetException ite) {
                Throwable cause = ite.getCause();
                if (cause instanceof RuntimeException re) throw re;
                throw new IllegalStateException("Callback failed", cause);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("Callback inaccessible", e);
            }
        }
    }

    private DomainCallback.Kind mapKind(LifecyclePhase p) {
        return switch (p) {
            case CREATE      -> DomainCallback.Kind.CREATE;
            case UPDATE      -> DomainCallback.Kind.UPDATE;
            case DELETE      -> DomainCallback.Kind.DELETE;
            case SOFT_DELETE -> DomainCallback.Kind.SOFT_DELETE;
        };
    }
}
```

### 16.6. Outbox в `onBeforeCommit`

```java
@Override
protected void onBeforeCommit(LifecyclePhase phase) {
    super.onBeforeCommit(phase);
    outboxWriter.write(new CustomerChangedEvent(getId(), phase));
}
```

`OutboxWriter` использует `@Transactional(propagation = MANDATORY)`, работает в текущей TX (см. §17).

---
## 17. Outbox + Spring Cloud Stream (требование 3)

### 17.1. Таблица `event_outbox`

```sql
CREATE TABLE event_outbox (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type  BIGINT       NOT NULL,
    aggregate_id    VARCHAR(128) NOT NULL,
    event_name      VARCHAR(255) NOT NULL,    -- стабильное имя из @DomainEvent.stableName, НЕ FQN
    event_version   INT          NOT NULL DEFAULT 1,
    payload         JSONB        NOT NULL,
    headers         JSONB        NOT NULL DEFAULT '{}'::jsonb,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),

    next_attempt_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    attempts        INT          NOT NULL DEFAULT 0,
    max_attempts    INT          NOT NULL DEFAULT 16,
    dead            BOOLEAN      NOT NULL DEFAULT false,
    last_error      TEXT,
    published_at    TIMESTAMPTZ
);

CREATE INDEX outbox_pending_idx
    ON event_outbox (next_attempt_at)
    WHERE published_at IS NULL AND dead = false;

CREATE INDEX outbox_dead_idx
    ON event_outbox (created_at)
    WHERE dead = true;

CREATE INDEX outbox_published_idx
    ON event_outbox (published_at)
    WHERE published_at IS NOT NULL;

CREATE TABLE outbox_processed_messages (
    message_id   UUID         PRIMARY KEY,
    consumer     VARCHAR(255) NOT NULL,
    processed_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
```

### 17.2. `DomainEventRegistry` — стабильные имена событий

`Class.forName(eventType)` — refactoring-hostile. При переносе класса события в другой пакет старые сообщения в Kafka не десериализуются. Решение — реестр со стабильными именами через `@DomainEvent`:

```java
@Component
public class DomainEventRegistry implements SmartInitializingSingleton {

    @Autowired private ApplicationContext appCtx;
    @Autowired private BootstrapProperties props;

    private final Map<String, Class<?>> byName  = new HashMap<>();
    private final Map<Class<?>, String> byClass = new HashMap<>();

    @Override
    public void afterSingletonsInstantiated() {
        try (var sr = new ClassGraph()
                .acceptPackages(props.getScanPackages().toArray(String[]::new))
                .enableAllInfo()
                .scan()) {
            for (var ci : sr.getClassesWithAnnotation(DomainEvent.class.getName())) {
                Class<?> cls = ci.loadClass();
                DomainEvent ann = cls.getAnnotation(DomainEvent.class);
                if (byName.containsKey(ann.stableName())) {
                    throw new BootstrapValidationException(
                        "Duplicate @DomainEvent.stableName: " + ann.stableName() +
                        " on " + byName.get(ann.stableName()) + " and " + cls);
                }
                byName.put(ann.stableName(), cls);
                byClass.put(cls, ann.stableName());
            }
        }
    }

    public Class<?> resolve(String stableName) {
        var c = byName.get(stableName);
        if (c == null) throw new IllegalArgumentException(
            "Unknown @DomainEvent stableName: " + stableName +
            ". Registered: " + byName.keySet());
        return c;
    }

    public String nameOf(Class<?> cls) {
        var n = byClass.get(cls);
        if (n == null) throw new IllegalArgumentException(
            "Class is not a registered @DomainEvent: " + cls);
        return n;
    }

    public int versionOf(Class<?> cls) {
        DomainEvent ann = cls.getAnnotation(DomainEvent.class);
        if (ann == null) throw new IllegalArgumentException(
            "Class is not a registered @DomainEvent: " + cls);
        return ann.version();
    }
}
```

Пример объявления события:

```java
@DomainEvent(stableName = "OrderPlaced.v1", version = 1)
public record OrderPlaced(
    UUID orderId,
    UUID customerId,
    Money total
) {}
```

При мажорном breaking-изменении — `OrderPlaced.v2` рядом с `.v1`, оба зарегистрированы; обработчики решают, что делать со старой версией.

ArchUnit-правило: `Class.forName(...)` запрещён в `core-eventing` и saga-классах:

```java
@ArchTest
static final ArchRule no_class_forname_in_eventing =
    noClasses().that().resideInAPackage("..core.eventing..")
               .should().callMethod(Class.class, "forName", String.class);
```

### 17.3. `OutboxWriter` — заполнение в текущей TX

`outboxObjectMapper` — отдельный bean, **без** `AccessAwareSerializerModifier`.

```java
@Component
@RequiredArgsConstructor
public class OutboxWriter {

    @Qualifier("outboxObjectMapper")
    private final ObjectMapper outboxMapper;
    private final OutboxJpaRepository repo;
    private final MetadataSnapshotProvider snapshots;
    private final OutboxHeaderEnricher headerEnricher;
    private final DomainEventRegistry events;

    @Transactional(propagation = Propagation.MANDATORY)
    public void write(Object event) {
        String stableName = events.nameOf(event.getClass());
        int version       = events.versionOf(event.getClass());

        // Извлекаем aggregateClass из metadata события (соглашение: метод aggregateClass())
        // или через DomainEventMeta-helper. Для упрощения — записываем typeId/id из payload.
        AggregateRef refMeta = extractAggregateRef(event);

        var entity = new OutboxEntity();
        entity.setAggregateType(refMeta.typeId());
        entity.setAggregateId(refMeta.idRaw());
        entity.setEventName(stableName);                   // СТАБИЛЬНОЕ ИМЯ, не FQN
        entity.setEventVersion(version);
        try {
            entity.setPayload(outboxMapper.writeValueAsString(event));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Outbox serialization failed", e);
        }
        entity.setHeaders(buildHeaders(event));
        repo.save(entity);
    }

    private String buildHeaders(Object event) {
        Map<String, String> headers = new LinkedHashMap<>(headerEnricher.captureTrace());
        headers.put("schemaVersion", String.valueOf(events.versionOf(event.getClass())));
        try { return outboxMapper.writeValueAsString(headers); }
        catch (JsonProcessingException e) { return "{}"; }
    }

    /**
     * Соглашение: события — record'ы, реализующие интерфейс DomainEventEnvelope с aggregateRef().
     * Альтернатива — помечать aggregate-id поле через @AggregateIdField; bootstrap-time валидация.
     */
    private AggregateRef extractAggregateRef(Object event) {
        if (event instanceof DomainEventEnvelope e) return e.aggregateRef();
        throw new IllegalArgumentException(
            "Event " + event.getClass() + " must implement DomainEventEnvelope");
    }
}

public interface DomainEventEnvelope {
    AggregateRef aggregateRef();
}

public record AggregateRef(long typeId, String idRaw) {}
```

### 17.4. `OutboxHeaderEnricher` — полный W3C Trace Context + baggage

```java
@Component
@RequiredArgsConstructor
public class OutboxHeaderEnricher {

    private final Tracer tracer;

    public Map<String, String> captureTrace() {
        Map<String, String> out = new LinkedHashMap<>();
        TraceContext ctx = tracer.currentTraceContext().context();
        if (ctx != null) {
            // W3C Trace Context
            out.put("traceparent", formatTraceparent(ctx));
            String tracestate = ctx.traceState();
            if (tracestate != null && !tracestate.isEmpty()) {
                out.put("tracestate", tracestate);
            }
        }
        // Micrometer baggage
        for (var entry : Baggage.current().getEntries()) {
            out.put("baggage-" + entry.getKey(), entry.getValue().getValue());
        }
        return out;
    }

    private String formatTraceparent(TraceContext ctx) {
        // version-traceId-spanId-flags
        return String.format("00-%s-%s-%s",
            ctx.traceId(),
            ctx.spanId(),
            ctx.sampled() != null && ctx.sampled() ? "01" : "00");
    }
}
```

### 17.5. `OutboxPoller` — per-message транзакция, partition key

Критическое решение: **транзакция на сообщение**, не на batch.

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxPoller {

    private final OutboxJpaRepository repo;
    private final OutboxProcessor processor;
    private final MeterRegistry meter;

    @Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms:1000}")
    public void poll() {
        List<UUID> candidateIds = repo.findCandidateIds(50);
        for (UUID id : candidateIds) {
            try {
                processor.processOne(id);
            } catch (Exception e) {
                log.warn("Outbox poll for id={} failed: {}", id, e.toString());
                meter.counter("ddd.outbox.poll_failed").increment();
            }
        }
    }
}

@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxProcessor {

    private final OutboxJpaRepository repo;
    private final StreamBridge streamBridge;
    private final BackoffStrategy backoff;
    private final MeterRegistry meter;
    @Qualifier("outboxObjectMapper")
    private final ObjectMapper outboxMapper;

    /** Каждое сообщение — отдельная транзакция. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 30)
    public void processOne(UUID id) {
        var msg = repo.lockForProcessing(id).orElse(null);
        if (msg == null) return;
        if (msg.isPublished() || msg.isDead()) return;

        try {
            String partitionKey = msg.getAggregateType() + ":" + msg.getAggregateId();
            Map<String, Object> headers = parseHeaders(msg.getHeaders());

            MessageBuilder<String> mb = MessageBuilder
                .withPayload(msg.getPayload())
                .setHeader(KafkaHeaders.KEY,    partitionKey)
                .setHeader("eventName",         msg.getEventName())     // СТАБИЛЬНОЕ ИМЯ
                .setHeader("eventVersion",      msg.getEventVersion())
                .setHeader("aggregateType",     msg.getAggregateType())
                .setHeader("aggregateId",       msg.getAggregateId())
                .setHeader("messageId",         msg.getId().toString());

            // Полный набор tracing-headers
            for (var e : headers.entrySet()) mb.setHeader(e.getKey(), e.getValue());

            boolean ok = streamBridge.send("domainEvents-out-0", mb.build());
            if (!ok) throw new IllegalStateException("StreamBridge.send returned false");

            msg.setPublishedAt(Instant.now());
            repo.save(msg);
            meter.counter("ddd.outbox.published",
                "aggregateType", String.valueOf(msg.getAggregateType()),
                "eventName",     msg.getEventName()).increment();

        } catch (Exception e) {
            int attempts = msg.getAttempts() + 1;
            msg.setAttempts(attempts);
            msg.setLastError(e.toString());
            if (attempts >= msg.getMaxAttempts()) {
                msg.setDead(true);
                meter.counter("ddd.outbox.dead").increment();
                log.error("Outbox message {} marked DEAD after {} attempts", id, attempts);
            } else {
                msg.setNextAttemptAt(Instant.now().plus(backoff.next(attempts)));
                meter.counter("ddd.outbox.retry").increment();
            }
            repo.save(msg);
        }
    }

    private Map<String, Object> parseHeaders(String json) {
        try { return outboxMapper.readValue(json, new TypeReference<>() {}); }
        catch (Exception e) { return Map.of(); }
    }
}
```

### 17.6. `BackoffStrategy`

```java
public interface BackoffStrategy {
    Duration next(int attempt);
}

@Component
public class ExponentialJitterBackoff implements BackoffStrategy {

    private static final Duration BASE = Duration.ofSeconds(1);
    private static final Duration CAP  = Duration.ofMinutes(15);

    @Override
    public Duration next(int attempt) {
        // exponential: BASE * 2^(attempt-1), bounded by CAP, with ±25% jitter
        long expMs = BASE.toMillis() * (1L << Math.min(attempt - 1, 30));
        long bounded = Math.min(expMs, CAP.toMillis());
        double jitter = 0.75 + ThreadLocalRandom.current().nextDouble() * 0.5;
        return Duration.ofMillis((long) (bounded * jitter));
    }
}
```

### 17.7. `OutboxJpaRepository`

```java
public interface OutboxJpaRepository extends JpaRepository<OutboxEntity, UUID> {

    @Query(value = """
        SELECT id FROM event_outbox
         WHERE published_at IS NULL AND dead = false AND next_attempt_at <= now()
         ORDER BY created_at
         LIMIT :limit
        """, nativeQuery = true)
    List<UUID> findCandidateIds(@Param("limit") int limit);

    @Query(value = """
        SELECT * FROM event_outbox
         WHERE id = :id
         FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    Optional<OutboxEntity> lockForProcessing(@Param("id") UUID id);

    @Modifying
    @Query("DELETE FROM OutboxEntity o WHERE o.publishedAt < :before")
    int deletePublishedBefore(@Param("before") Instant before);
}
```

### 17.8. Cleanup-job

```java
@Component
@RequiredArgsConstructor
public class OutboxCleanupJob {
    private final OutboxJpaRepository repo;

    @Scheduled(cron = "0 0 3 * * *")     // ежедневно 3:00
    @Transactional
    public void cleanup() {
        Instant cutoff = Instant.now().minus(Duration.ofDays(7));
        int deleted = repo.deletePublishedBefore(cutoff);
        log.info("Outbox cleanup deleted {} rows older than {}", deleted, cutoff);
    }
}
```

### 17.9. Spring Cloud Stream consumer + idempotency

```java
@Configuration
@RequiredArgsConstructor
public class StreamConfig {

    @Bean
    public Consumer<Message<String>> domainEventsIn(
            DomainEventDispatcher dispatcher,
            OutboxConsumerIdempotency idempotency) {
        return msg -> {
            String messageId = (String) msg.getHeaders().get("messageId");
            String consumerName = "domainEventsIn";
            if (messageId != null && !idempotency.markIfNew(messageId, consumerName)) {
                log.debug("Duplicate outbox message {} for {}, skip", messageId, consumerName);
                return;
            }
            dispatcher.dispatch(msg);
        };
    }
}

@Service
@RequiredArgsConstructor
public class OutboxConsumerIdempotency {

    @PersistenceContext
    private final EntityManager em;

    /**
     * Первый раз увидели сообщение — true; уже обработано — false.
     * Использует уникальный constraint outbox_processed_messages.message_id.
     */
    @Transactional
    public boolean markIfNew(String messageId, String consumer) {
        try {
            int inserted = em.createNativeQuery("""
                INSERT INTO outbox_processed_messages (message_id, consumer)
                VALUES (CAST(?1 AS UUID), ?2)
                ON CONFLICT (message_id) DO NOTHING
                """)
              .setParameter(1, messageId)
              .setParameter(2, consumer)
              .executeUpdate();
            return inserted > 0;
        } catch (Exception e) {
            log.warn("Idempotency check failed: {}", e.toString());
            return true;   // fail-open
        }
    }
}
```

### 17.10. Spring Cloud Stream binder config

```yaml
spring:
  cloud:
    stream:
      function:
        definition: domainEventsIn
      bindings:
        domainEventsIn-in-0:
          destination: domain-events
          group: orderservice
          consumer:
            concurrency: 4
            max-attempts: 1            # ретраи через outbox-poller, не через consumer
        domainEvents-out-0:
          destination: domain-events
          producer:
            partition-key-expression: headers['kafka_messageKey']
      kafka:
        binder:
          brokers: ${KAFKA_BROKERS}
        bindings:
          domainEventsIn-in-0:
            consumer:
              enable-dlq: true
              dlq-name: domain-events-dlq
              start-offset: earliest
              ack-mode: MANUAL
```

---

## 18. Распределённая координация: Axon (saga-only), Temporal, Idempotency (требование 2)

### 18.1. Карта применения

| Сценарий | Инструмент |
|---|---|
| Многошаговая бизнес-операция в рамках одной БД с компенсациями | **Axon Saga (state-stored)** — **только для внутренней оркестрации** |
| Long-running workflow с timer'ами, signal'ами, ретраями (часы/дни) | **Temporal Workflow** |
| Защита от двойной инициации (POST дважды) | `IdempotencyGuard` (Redis SETNX + Redis Streams + renewable lock) |
| Идемпотентность outbox-consumer'а | `outbox_processed_messages` (см. §17.9) |

**КРИТИЧНО — единый маршрут публикации:**
- **Axon `EventBus`** — внутренний bus, **не публикует наружу**. Используется для саг (`@SagaEventHandler` подписывается на события из этого внутреннего bus'а).
- **Все доменные события для внешних потребителей** — идут через **outbox → Kafka**.
- **Saga подписывается на Kafka** через Spring Cloud Stream consumer (не через `@EventHandler` Axon-bus'а), который потом ре-эмитит сообщение во внутренний Axon-bus как `EventMessage`. Идентификация события — по `eventName` header'у через `DomainEventRegistry` (не `Class.forName`).

Это даёт:
- Одно событие — один маршрут публикации (outbox).
- ACID outbox-гарантии для всех external observers.
- Saga изолирована — её state-передача работает на Axon-механизмах внутри JVM.

### 18.2. Axon — единый EntityManagerFactory, EventBus отключён

```java
@Configuration
public class AxonConfig {

    @Bean
    public EntityManagerProvider entityManagerProvider(EntityManager em) {
        return new ContainerManagedEntityManagerProvider();
    }

    /**
     * SagaStore — да; EventStorageEngine — нет.
     * Saga state хранится через JpaSagaStore. События НЕ хранятся в Axon — вместо этого
     * саги получают события из Kafka через KafkaSagaBridge.
     */
    @Bean
    public SagaStore<Object> sagaStore(EntityManagerProvider emp) {
        return JpaSagaStore.builder()
            .entityManagerProvider(emp)
            .build();
    }

    /**
     * In-memory event bus — события НЕ персистентны, не публикуются наружу.
     * Используется только для маршрутизации Kafka→Saga.
     */
    @Bean
    public EventBus inMemoryEventBus() {
        return SimpleEventBus.builder().build();
    }

    @Bean
    public Serializer axonSerializer(@Qualifier("outboxObjectMapper") ObjectMapper om) {
        return JacksonSerializer.builder().objectMapper(om).build();
    }
}
```

ArchUnit:

```java
@ArchTest
static final ArchRule no_jpa_event_storage_engine =
    noClasses().should().dependOnClassesThat()
               .haveFullyQualifiedName(JpaEventStorageEngine.class.getName());

@ArchTest
static final ArchRule eventgateway_only_in_workflow =
    noClasses().that().resideOutsideOfPackage("..core.workflow..")
               .and().resideOutsideOfPackage("..saga..")
               .should().dependOnClassesThat()
               .haveFullyQualifiedName(EventGateway.class.getName());
```

### 18.3. `KafkaSagaBridge` — мост от Kafka к Axon-bus'у через DomainEventRegistry

```java
@Component
@RequiredArgsConstructor
public class KafkaSagaBridge {

    private final EventBus axonEventBus;
    @Qualifier("outboxObjectMapper")
    private final ObjectMapper outboxMapper;
    private final UserAccessContextLoader ctxLoader;
    private final AccessContextHolder holder;
    private final SystemAccessContexts systems;
    private final DomainEventRegistry events;

    /**
     * Принимает сообщения из общего Kafka-consumer'а и ре-эмитит их в Axon EventBus
     * для саг, которые на них подписаны. Резолв класса через стабильное имя, не Class.forName.
     */
    public void onKafkaMessage(Message<String> msg) {
        try {
            String eventName = (String) msg.getHeaders().get("eventName");
            Class<?> eventClass = events.resolve(eventName);
            Object event = outboxMapper.readValue(msg.getPayload(), eventClass);

            // Восстанавливаем AccessContext из headers (если был, иначе systemReadWrite)
            String principalIdRaw = (String) msg.getHeaders().get("principalIdRaw");
            String principalTypeIdStr = (String) msg.getHeaders().get("principalTypeId");
            AccessContext ctx;
            if (principalIdRaw == null) {
                ctx = systems.systemReadWrite();
            } else if (AccessContext.SYSTEM_PRINCIPAL_ID.equals(principalIdRaw)) {
                ctx = "true".equals(msg.getHeaders().get("isMaxPrivileged"))
                    ? systems.maxPrivileges() : systems.systemReadWrite();
            } else {
                ctx = ctxLoader.loadFor(Long.parseLong(principalTypeIdStr), principalIdRaw);
            }

            try (var ignored = holder.bind(ctx)) {
                Map<String, Object> meta = new HashMap<>(msg.getHeaders());
                axonEventBus.publish(new GenericEventMessage<>(event, meta));
            }
        } catch (Exception e) {
            log.error("KafkaSagaBridge: failed to relay event", e);
            throw new RuntimeException(e);
        }
    }
}

@Configuration
public class SagaStreamConfig {

    @Bean
    public Consumer<Message<String>> sagaEvents(KafkaSagaBridge bridge) {
        return bridge::onKafkaMessage;
    }
}
```

### 18.4. `AxonAccessContext`-interceptors

Сага получает событие через Axon-bus уже с восстановленным `AccessContext` (см. `KafkaSagaBridge` выше). Если saga публикует **свои** служебные команды через `CommandGateway` — interceptor пробрасывает дальше:

```java
@Component
@RequiredArgsConstructor
public class AxonAccessContextDispatchInterceptor
        implements MessageDispatchInterceptor<CommandMessage<?>> {

    private final AccessContextHolder holder;

    @Override
    public BiFunction<Integer, CommandMessage<?>, CommandMessage<?>> handle(
            List<? extends CommandMessage<?>> messages) {
        return (i, m) -> {
            var ctxOpt = holder.tryGet();
            if (ctxOpt.isEmpty()) return m;
            var ctx = ctxOpt.get();
            return m.andMetaData(Map.of(
                "principalTypeId", ctx.principalRef().targetTypeId(),
                "principalIdRaw",  ctx.principalRef().targetIdRaw(),
                "isSystem",        String.valueOf(ctx.isSystem()),
                "isMaxPrivileged", String.valueOf(ctx.isSystemMaxPrivileged())
            ));
        };
    }
}

@Component
@RequiredArgsConstructor
public class AxonAccessContextRestoringInterceptor
        implements MessageHandlerInterceptor<Message<?>> {

    private final AccessContextHolder holder;
    private final UserAccessContextLoader ctxLoader;
    private final SystemAccessContexts systems;

    @Override
    public Object handle(UnitOfWork<? extends Message<?>> uow, InterceptorChain chain) throws Exception {
        Message<?> msg = uow.getMessage();
        var meta = msg.getMetaData();

        Object principalIdRaw = meta.get("principalIdRaw");
        if (principalIdRaw == null) {
            return chain.proceed();
        }

        AccessContext ctx;
        if (Boolean.parseBoolean(String.valueOf(meta.get("isMaxPrivileged")))) {
            ctx = systems.maxPrivileges();
        } else if (AccessContext.SYSTEM_PRINCIPAL_ID.equals(principalIdRaw)) {
            ctx = systems.systemReadWrite();
        } else {
            long principalTypeId = Long.parseLong(String.valueOf(meta.get("principalTypeId")));
            ctx = ctxLoader.loadFor(principalTypeId, String.valueOf(principalIdRaw));
        }

        try (var ignored = holder.bind(ctx)) {
            return chain.proceed();
        }
    }
}
```

### 18.5. Saga — пример

```java
@Saga
public class OrderProcessingSaga {

    @Autowired private transient PaymentService payments;
    @Autowired private transient OutboxWriter outboxWriter;

    private OrderId orderId;
    private Money total;

    @StartSaga
    @SagaEventHandler(associationProperty = "orderId")
    public void on(OrderPlaced ev) {
        this.orderId = ev.orderId();
        this.total   = ev.total();
        // Публикация наружу — ТОЛЬКО через outbox.
        outboxWriter.write(new ChargeRequested(orderId, total));
    }

    @SagaEventHandler(associationProperty = "orderId")
    public void on(ChargeSucceeded ev) {
        outboxWriter.write(new ShipmentRequested(orderId));
    }

    @SagaEventHandler(associationProperty = "orderId")
    public void on(ChargeFailed ev) {
        outboxWriter.write(new OrderCancelled(orderId, "payment-failed"));
        SagaLifecycle.end();
    }
}
```

### 18.6. Saga с критичной consistency

```yaml
axon:
  eventhandling:
    processors:
      OrderProcessingSaga:
        mode: tracking
        batch-size: 1                # CRITICAL: предотвращаем concurrent-update saga state
        thread-count: 1
```

### 18.7. Temporal — long-running workflow

```java
@WorkflowInterface
public interface OnboardingWorkflow {
    @WorkflowMethod
    void start(UUID customerId);
}

public class OnboardingWorkflowImpl implements OnboardingWorkflow {

    private final OnboardingActivities activities = Workflow.newActivityStub(
        OnboardingActivities.class,
        ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofMinutes(5))
            .setRetryOptions(RetryOptions.newBuilder()
                .setInitialInterval(Duration.ofSeconds(2))
                .setMaximumAttempts(5)
                .build())
            .build());

    @Override
    public void start(UUID customerId) {
        activities.sendWelcomeEmail(customerId);
        Workflow.sleep(Duration.ofDays(3));
        if (!activities.isProfileCompleted(customerId)) {
            activities.sendReminder(customerId);
        }
        Workflow.sleep(Duration.ofDays(7));
        activities.recheckActivity(customerId);
    }
}
```

### 18.8. `IdempotencyGuard` через Redis Streams

Pub/Sub fire-and-forget принципиально хрупок: если subscriber зарегистрировался **после** publish'а, сообщение потеряно. Решение — Redis Streams (`XADD` + `XREAD BLOCK`). Stream хранит сообщение как лог: late subscriber всё равно прочитает.

```java
@FunctionalInterface
public interface IdemAction<T> {
    T execute() throws Exception;
}

@Component
@RequiredArgsConstructor
public class IdempotencyGuard {

    private static final Duration LOCK_TTL    = Duration.ofMinutes(5);
    private static final Duration RENEW_EVERY = Duration.ofMinutes(2);
    private static final Duration STREAM_TTL  = Duration.ofHours(24);

    private final StringRedisTemplate redis;
    @Qualifier("outboxObjectMapper")
    private final ObjectMapper om;
    private final ScheduledExecutorService renewExecutor;

    public <T> T executeOnce(String key, Class<T> resultType,
                             IdemAction<T> action) throws Exception {
        String resKey    = "idem:result:" + key;
        String lockKey   = "idem:lock:"   + key;
        String streamKey = "idem:stream:" + key;
        String token     = UUID.randomUUID().toString();

        // Fast path: результат уже есть
        String cached = redis.opsForValue().get(resKey);
        if (cached != null) return om.readValue(cached, resultType);

        Boolean acquired = redis.opsForValue().setIfAbsent(lockKey, token, LOCK_TTL);
        if (Boolean.FALSE.equals(acquired)) {
            return waitForResult(streamKey, resKey, resultType);
        }

        ScheduledFuture<?> renewer = renewExecutor.scheduleAtFixedRate(
            () -> renewLock(lockKey, token),
            RENEW_EVERY.toSeconds(), RENEW_EVERY.toSeconds(), TimeUnit.SECONDS
        );

        try {
            T result = action.execute();
            String json = om.writeValueAsString(result);
            redis.opsForValue().set(resKey, json, Duration.ofHours(24));

            // Записываем в Stream — поздние waiter'ы тоже прочитают
            ObjectRecord<String, Map<String, String>> rec = StreamRecords.objectBacked(
                Map.of("status", "ok")).withStreamKey(streamKey);
            redis.opsForStream().add(rec);
            redis.expire(streamKey, STREAM_TTL);
            return result;

        } catch (Exception e) {
            ObjectRecord<String, Map<String, String>> rec = StreamRecords.objectBacked(
                Map.of("status", "err", "error", String.valueOf(e.getMessage())))
                .withStreamKey(streamKey);
            redis.opsForStream().add(rec);
            redis.expire(streamKey, STREAM_TTL);
            throw e;
        } finally {
            renewer.cancel(false);
            releaseLock(lockKey, token);
        }
    }

    private void renewLock(String lockKey, String token) {
        redis.execute(new SessionCallback<Object>() {
            @SuppressWarnings({"rawtypes","unchecked"})
            @Override public Object execute(RedisOperations ops) throws DataAccessException {
                ops.watch(lockKey);
                String current = (String) ops.opsForValue().get(lockKey);
                if (!token.equals(current)) {
                    ops.unwatch();
                    return null;
                }
                ops.multi();
                ops.expire(lockKey, LOCK_TTL);
                return ops.exec();
            }
        });
    }

    private void releaseLock(String lockKey, String token) {
        redis.execute(new SessionCallback<Object>() {
            @SuppressWarnings({"rawtypes","unchecked"})
            @Override public Object execute(RedisOperations ops) throws DataAccessException {
                ops.watch(lockKey);
                String current = (String) ops.opsForValue().get(lockKey);
                if (!token.equals(current)) { ops.unwatch(); return null; }
                ops.multi();
                ops.delete(lockKey);
                return ops.exec();
            }
        });
    }

    /**
     * XREAD BLOCK — ждём появления записи в стриме. Если writer уже опубликовал ДО subscribe —
     * запись уже есть и читается немедленно. Если ещё не опубликовал — блокируемся до timeout'а.
     */
    private <T> T waitForResult(String streamKey, String resKey, Class<T> resultType)
            throws Exception {
        long deadlineMs = System.currentTimeMillis() + LOCK_TTL.toMillis() + 30_000;

        // Сначала — pull уже накопившихся сообщений (если writer успел до нашего subscribe)
        StreamReadOptions opts = StreamReadOptions.empty()
                .count(10)
                .block(Duration.ofMillis(500));

        // Читаем с самого начала стрима — наш ключ уникальный и любые записи в нём — наши
        String lastId = "0";
        while (System.currentTimeMillis() < deadlineMs) {
            List<MapRecord<String, Object, Object>> records = redis.opsForStream().read(
                opts, StreamOffset.create(streamKey, ReadOffset.from(lastId)));
            if (records != null && !records.isEmpty()) {
                MapRecord<String, Object, Object> last = records.get(records.size() - 1);
                String status = String.valueOf(last.getValue().get("status"));
                if ("ok".equals(status)) {
                    String cached = redis.opsForValue().get(resKey);
                    if (cached == null) throw new IllegalStateException(
                        "Stream signaled ok but result missing for " + resKey);
                    return om.readValue(cached, resultType);
                }
                throw new IllegalStateException("Idem operation failed: " +
                    last.getValue().get("error"));
            }
            // Продолжаем ждать с того же `0` (либо last seen ID для оптимизации,
            // но при single-record-per-key стриме это эквивалентно)
        }
        throw new TimeoutException("Idempotent operation timeout for key " + resKey);
    }
}

@Configuration
public class IdempotencyConfig {

    @Bean(destroyMethod = "shutdown")
    public ScheduledExecutorService renewExecutor(TaskDecorator decorator) {
        var pool = new ScheduledThreadPoolExecutor(2, r -> {
            Thread t = new Thread(decorator.decorate(r), "idem-renew");
            t.setDaemon(true);
            return t;
        });
        pool.setRemoveOnCancelPolicy(true);
        return pool;
    }
}
```

**Гарантии:**
- Если writer опубликовал в Stream **до** subscribe'а waiter'а — waiter всё равно прочитает запись (Streams персистентны).
- TTL стрима 24h — память не растёт неограниченно.
- Нет race'а между «прочитал resKey (null)» и «subscribe'нулся» — это окно теперь не critical.

### 18.9. Контроллер с `@Idempotent`

```java
@PostMapping("/orders")
@Idempotent(headerName = "Idempotency-Key", timeout = "PT24H")
public OrderResponse createOrder(@RequestBody @Valid CreateOrderRequest req,
                                 @RequestHeader("Idempotency-Key") String key) {
    return orderService.create(req);
}
```

`@Idempotent` AOP-аспект:

```java
@Aspect
@Component
@RequiredArgsConstructor
public class IdempotentAspect {

    private final IdempotencyGuard guard;

    @Around("@annotation(idempotent)")
    public Object around(ProceedingJoinPoint pjp, Idempotent idempotent) throws Throwable {
        HttpServletRequest req = ((ServletRequestAttributes) RequestContextHolder
                .currentRequestAttributes()).getRequest();
        String key = req.getHeader(idempotent.headerName());
        if (key == null || key.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Header " + idempotent.headerName() + " is required");
        }
        Class<?> returnType = ((MethodSignature) pjp.getSignature()).getReturnType();
        return guard.executeOnce(key, returnType, () -> {
            try { return pjp.proceed(); }
            catch (Throwable t) {
                if (t instanceof Exception e) throw e;
                throw new RuntimeException(t);
            }
        });
    }
}
```

---
## 19. AccessProjection — «двойник» доступа (требование 10)

### 19.1. Архитектура (Variant B — рекомендованная)

`AccessProjection` — это компактное JSON-представление, описывающее **что пользователь МОЖЕТ делать** с конкретной instance агрегата. Прикрепляется к response как envelope `{ data, _access, _accessOverrides }`.

**Variant B ("план без values")** — `AccessProjectionTemplate` хранит **структуру плана** (какие поля есть, какие у них defaultAccess), но **не значения**. Per-instance values вычисляются on-demand в `AccessProjectionAdvice.wrapPage`. Cache хранит **baseline** view (доступ для пользователя БЕЗ instance ACL): `null` для baseline-hidden, реальное значение для baseline-visible.

Преимущества:
- 1 cache hit → много пользователей с одинаковым типом + полностью baseline'ным доступом получают тот же hit.
- Per-instance overrides (через `ownAccess`) не плодят cache-keys.

### 19.2. `AccessProjectionTemplate` + двухуровневый fingerprint (cross-user / per-instance)

`fingerprint` бывает двух типов:

1. **Cross-user fingerprint** (default) — строится только из effective-bit'ов плана `(fieldId, canRead, canWrite, writeOnce, writeOnly)`. Пользователи A и B с разными JWT, но одинаковым набором фактически-разрешённых полей, получают одинаковый fingerprint и шарят projection-cache. Это применяется к **подавляющему большинству** типов, у которых нет instance-level ACL.

2. **Per-instance fingerprint** — применяется только если у пользователя в `AccessMetricPayload.instanceWriteAcl` есть **непустая** запись для целевого `typeId` (т.е. есть instance-level grant). В этом случае fingerprint включает `instanceId` и `principalIdRaw` — cache становится per-(user, instance), что обеспечивает корректность видимости для разных пользователей того же fingerprint'а cross-user-уровня.

Trade-off: при наличии instance-ACL'а projection-cache становится per-user, но instance-ACL — это редкий сценарий (явный grant конкретному пользователю на конкретные instance'ы конкретного типа). Большинство типов работают на cross-user fingerprint'е с высоким cache-hit-rate.

```java
public record AccessProjectionTemplate(
    long typeId,
    List<FieldEntry> entries,
    String fingerprint                  // cross-user или per-instance, см. ниже
) {
    public record FieldEntry(
        long fieldId,
        String name,
        AccessLevel effectiveByField,   // эффективный уровень БЕЗ instance ACL
        boolean canRead,
        boolean canWrite,
        boolean writeOnce,
        boolean writeOnly
    ) {}

    /** Cross-user fingerprint: SHA-256(typeId | sorted(fieldId | canRead | canWrite | writeOnce | writeOnly)). */
    public static String crossUserFingerprintOf(long typeId, List<FieldEntry> entries) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(Long.toString(typeId).getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            entries.stream()
                .sorted(Comparator.comparingLong(FieldEntry::fieldId))
                .forEach(e -> {
                    md.update(Long.toString(e.fieldId()).getBytes(StandardCharsets.UTF_8));
                    md.update((byte) (e.canRead()    ? 1 : 0));
                    md.update((byte) (e.canWrite()   ? 1 : 0));
                    md.update((byte) (e.writeOnce()  ? 1 : 0));
                    md.update((byte) (e.writeOnly()  ? 1 : 0));
                    md.update((byte) 0);
                });
            return Base64.getUrlEncoder().withoutPadding().encodeToString(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Per-instance fingerprint: cross-user-fingerprint | instanceId | principalIdRaw.
     * Используется ТОЛЬКО когда у пользователя есть instance-level ACL для данного typeId.
     */
    public static String perInstanceFingerprintOf(String crossUserFp, String instanceIdRaw, String principalIdRaw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(crossUserFp.getBytes(StandardCharsets.UTF_8));
            md.update((byte) '|');
            md.update(instanceIdRaw.getBytes(StandardCharsets.UTF_8));
            md.update((byte) '|');
            md.update(principalIdRaw.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
```

### 19.3. `AccessProjectionService` — построение плана и view

```java
@Service
@RequiredArgsConstructor
public class AccessProjectionService {

    private final MetadataSnapshotProvider snapshots;
    private final AccessResolver resolver;
    private final UserAccessProvider userAccess;
    @Qualifier("accessAwareObjectMapper")
    private final ObjectMapper accessMapper;
    @Qualifier("dtoObjectMapper")
    private final ObjectMapper dtoMapper;

    /**
     * Строит план для типа в текущем контексте БЕЗ instance ACL.
     * Эффективный уровень для каждого поля — пересечение defaultAccess × globalGrants
     * (instance.ownAccess НЕ учитывается).
     */
    public AccessProjectionTemplate templateFor(long typeId, AccessContext ctx) {
        AggregateDescriptor desc = snapshots.get().aggregate(typeId);
        List<AccessProjectionTemplate.FieldEntry> entries = new ArrayList<>(desc.fields().size());

        for (FieldDescriptor fd : desc.fields()) {
            // resolver.resolve(typeId, fieldId, instance=null, ctx) — без instance ACL
            AccessLevel lvl = resolver.resolve(typeId, fd.fieldId(), null, ctx);
            boolean canRead   = lvl.canRead();
            boolean canWrite  = lvl.canWrite(null);          // baseline: «при пустом current»
            boolean writeOnce = lvl.mode() == WriteMode.MODIFY_EMPTY;
            boolean writeOnly = lvl.mode() == WriteMode.WRITE_ONLY;
            entries.add(new AccessProjectionTemplate.FieldEntry(
                fd.fieldId(), fd.shortName(), lvl, canRead, canWrite, writeOnce, writeOnly));
        }
        String fingerprint = AccessProjectionTemplate.crossUserFingerprintOf(typeId, entries);
        return new AccessProjectionTemplate(typeId, List.copyOf(entries), fingerprint);
    }

    /**
     * Возвращает fingerprint для projection-cache:
     * - cross-user fingerprint, если у пользователя нет instance-ACL grant'а для typeId;
     * - per-instance fingerprint (включающий instanceIdRaw и principalIdRaw), если есть.
     *
     * `instance` может быть null — тогда возвращается cross-user-fingerprint вне зависимости
     * от наличия ACL (для page/list-level cache-keys, см. §21).
     */
    public String fingerprintFor(long typeId, @Nullable AbstractAggregate<?> instance, AccessContext ctx) {
        var template = templateFor(typeId, ctx);
        if (instance == null) return template.fingerprint();

        AccessMetric um = userAccess.metricFor(ctx);
        Set<String> instanceAcl = um.instanceWriteAcl().getOrDefault(typeId, Set.of());
        if (instanceAcl.isEmpty()) {
            // Нет instance-ACL для этого типа → cross-user cache-entry разделяется
            return template.fingerprint();
        }
        // Есть instance-ACL → fingerprint per-(user, instance) для гарантии корректности
        String instanceIdRaw = IdCodec.encode(instance.getId());
        String principalIdRaw = ctx.principalRef() != null ? ctx.principalRef().targetIdRaw() : "anon";
        return AccessProjectionTemplate.perInstanceFingerprintOf(
            template.fingerprint(), instanceIdRaw, principalIdRaw);
    }

    /**
     * Рендерит JSON-вид instance'а под текущим AccessContext'ом. Вызывается из:
     *   - AggregateReferenceSerializer (для вложенных refs);
     *   - AccessProjectionAdvice (для top-level response).
     */
    public JsonNode renderView(AbstractAggregate<?> instance, AccessContext ctx) {
        // accessAwareObjectMapper применит AccessAwareSerializerModifier,
        // который скроет HIDDEN/WRITE_ONLY поля per-field.
        return accessMapper.valueToTree(instance);
    }

    /** Для типизированного API — конвертация JsonNode → DTO. */
    public <D> D renderViewAsObject(AbstractAggregate<?> instance, AccessContext ctx,
                                    Class<D> dtoClass) {
        JsonNode tree = renderView(instance, ctx);
        return dtoMapper.convertValue(tree, dtoClass);
    }

    /**
     * Per-instance access-projection: возвращает overrides для полей, где instance.ownAccess
     * сужает или расширяет baseline-уровень (template).
     */
    public Map<String, AccessOverride> overridesFor(AbstractAggregate<?> instance,
                                                     AccessProjectionTemplate template,
                                                     AccessContext ctx) {
        Map<String, AccessOverride> out = new LinkedHashMap<>();
        for (var e : template.entries()) {
            AccessLevel withInstance = resolver.resolve(template.typeId(), e.fieldId(), instance, ctx);
            if (!withInstance.equals(e.effectiveByField())) {
                out.put(e.name(), new AccessOverride(
                    withInstance.canRead(),
                    withInstance.canWrite(currentValue(instance, e.fieldId())),
                    withInstance.mode() == WriteMode.MODIFY_EMPTY,
                    withInstance.mode() == WriteMode.WRITE_ONLY
                ));
            }
        }
        return out;
    }

    private Object currentValue(AbstractAggregate<?> instance, long fieldId) {
        long typeId = snapshots.get().typeIdOf(instance.getClass());
        FieldDescriptor fd = snapshots.get().aggregate(typeId).field(fieldId);
        return fd == null ? null : fd.read(instance);
    }

    public record AccessOverride(boolean canRead, boolean canWrite,
                                  boolean writeOnce, boolean writeOnly) {}
}
```

> **Изменение G9:** v8 имел один cross-user fingerprint, что давало data-leak при наличии `instanceWriteAcl`-grant'ов (пользователи A с allow-instance-X и B без allow-instance-X с одинаковым cross-user-fingerprint'ом видели один cache-entry). v9 — выбран trade-off «при instance-ACL fingerprint per-user», корректность приоритетнее cache-hit-rate для редкого случая.

### 19.4. `AccessProjectionCache`

```java
@Component
@RequiredArgsConstructor
public class AccessProjectionCache {

    private final RedisTemplate<String, Object> redis;
    @Qualifier("accessAwareObjectMapper")
    private final ObjectMapper accessMapper;

    /**
     * Загружает rendered view из кеша или вычисляет через loader.
     * Cache key включает fingerprint (структуру плана), но НЕ accessKeyHash —
     * разные JWT с одинаковыми правами шарят entry.
     */
    public JsonNode getOrLoad(long typeId, String idRaw, String key,
                              Supplier<JsonNode> loader) {
        Object cached = redis.opsForValue().get(key);
        if (cached instanceof String s) {
            try { return accessMapper.readTree(s); }
            catch (IOException ignored) {}
        }
        JsonNode view = loader.get();
        try {
            redis.opsForValue().set(key, accessMapper.writeValueAsString(view),
                                    Duration.ofMinutes(30));
        } catch (JsonProcessingException ignored) {}
        return view;
    }

    public void evict(long typeId, String idRaw) {
        // Точечная инвалидация по reverse-индексу (см. §21)
        Set<String> keys = redis.opsForSet().members("ref-deps:" + typeId + ":" + idRaw);
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }
}
```

### 19.5. `AccessProjectionAdvice` — оборачивание в envelope

```java
@RestControllerAdvice
@RequiredArgsConstructor
public class AccessProjectionAdvice implements ResponseBodyAdvice<Object> {

    private final AccessContextHolder holder;
    private final AccessProjectionService projectionService;
    private final RefBatchPrefetcher prefetcher;
    private final MetadataSnapshotProvider snapshots;
    private final RefResolutionCache requestCache;       // request-scoped
    private final ObjectProvider<PostLoadDropMarker> dropMarkerProvider;   // request-scoped

    @Override
    public boolean supports(MethodParameter ret, Class<? extends HttpMessageConverter<?>> c) {
        return ret.hasMethodAnnotation(AccessProjected.class);
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter ret, MediaType mt,
                                  Class<? extends HttpMessageConverter<?>> c,
                                  ServerHttpRequest req, ServerHttpResponse resp) {
        if (body == null) return null;
        var ctx = holder.tryGet().orElse(null);
        if (ctx == null) return body;

        // 1. Prefetch всех AggregateReference'ов (с активными filter'ами)
        prefetcher.prefetch(body);

        // 2. Обернуть top-level
        if (body instanceof AbstractAggregate<?> agg) {
            return wrapSingle(agg, ctx);
        }
        if (body instanceof Page<?> page) {
            return wrapPage(page, ctx);
        }
        if (body instanceof Iterable<?> it) {
            return wrapIterable(it, ctx);
        }
        return body;
    }

    private AccessEnvelope<JsonNode> wrapSingle(AbstractAggregate<?> agg, AccessContext ctx) {
        long typeId = snapshots.get().typeIdOf(agg.getClass());
        var template = projectionService.templateFor(typeId, ctx);
        JsonNode view = projectionService.renderView(agg, ctx);
        var overrides = projectionService.overridesFor(agg, template, ctx);
        return new AccessEnvelope<>(view, template, overrides);
    }

    private Object wrapPage(Page<?> page, AccessContext ctx) {
        // G4: фильтрация помеченных PostLoadAccessCheckListener'ом (MARK_AND_DROP-режим)
        PostLoadDropMarker marker = dropMarkerProvider.getIfAvailable();
        List<?> visibleContent = (marker == null)
            ? page.getContent()
            : page.getContent().stream().filter(o -> !marker.isMarked(o)).toList();
        boolean approximate = marker != null && marker.hadAnyDrops();

        if (visibleContent.isEmpty()) {
            return new PagedAccessEnvelope<>(List.of(), null, page.getPageable(),
                                             page.getTotalElements(), List.of(), approximate);
        }
        long typeId = snapshots.get().typeIdOf(visibleContent.get(0).getClass());
        var template = projectionService.templateFor(typeId, ctx);

        List<AccessEnvelope<JsonNode>> items = visibleContent.stream()
            .map(o -> {
                var agg = (AbstractAggregate<?>) o;
                JsonNode view = projectionService.renderView(agg, ctx);
                var overrides = projectionService.overridesFor(agg, template, ctx);
                return new AccessEnvelope<>(view, null, overrides);
            })
            .toList();

        return new PagedAccessEnvelope<>(items, template, page.getPageable(),
                                          page.getTotalElements(), List.of(), approximate);
    }

    private Object wrapIterable(Iterable<?> it, AccessContext ctx) {
        PostLoadDropMarker marker = dropMarkerProvider.getIfAvailable();
        List<AccessEnvelope<JsonNode>> out = new ArrayList<>();
        AccessProjectionTemplate template = null;
        for (var e : it) {
            if (!(e instanceof AbstractAggregate<?> agg)) continue;
            if (marker != null && marker.isMarked(agg)) continue;     // G4
            if (template == null) {
                long typeId = snapshots.get().typeIdOf(agg.getClass());
                template = projectionService.templateFor(typeId, ctx);
            }
            JsonNode view = projectionService.renderView(agg, ctx);
            var overrides = projectionService.overridesFor(agg, template, ctx);
            out.add(new AccessEnvelope<>(view, null, overrides));
        }
        return new ListAccessEnvelope<>(out, template);
    }
}

public record AccessEnvelope<T>(
    @JsonProperty("data") T data,
    @JsonProperty("_access") @JsonInclude(JsonInclude.Include.NON_NULL)
        AccessProjectionTemplate access,
    @JsonProperty("_accessOverrides") @JsonInclude(JsonInclude.Include.NON_EMPTY)
        Map<String, AccessProjectionService.AccessOverride> overrides
) {}

public record PagedAccessEnvelope<T>(
    @JsonProperty("data") List<AccessEnvelope<T>> data,
    @JsonProperty("_access") AccessProjectionTemplate access,
    @JsonProperty("page") Pageable page,
    @JsonProperty("totalElements") long totalElements,
    @JsonProperty("warnings") List<String> warnings,
    @JsonProperty("approximateTotal") boolean approximateTotal     // G4: true если post-load отбросил хотя бы одну строку
) {}

public record ListAccessEnvelope<T>(
    @JsonProperty("data") List<AccessEnvelope<T>> data,
    @JsonProperty("_access") AccessProjectionTemplate access
) {}
```

### 19.6. Использование в контроллере

```java
@RestController
@RequiredArgsConstructor
public class CustomerController {

    private final CustomerRepository repo;

    @GetMapping("/customers/{id}")
    @AccessProjected
    public CustomerAggregate findOne(@PathVariable UUID id) {
        return repo.findById(id).orElseThrow();
    }

    @GetMapping("/customers")
    @AccessProjected
    public Page<CustomerAggregate> page(@RequestParam(required = false) String q,
                                        Pageable pg) {
        return q == null ? repo.findAll(pg) : repo.findByEmailContaining(q, pg);
    }
}
```

Response для `GET /customers/{id}`:

```json
{
  "data": {
    "id": "...",
    "displayName": "Acme",
    "email": null,                         // HIDDEN per default — скрыто
    "passwordHash": null,                  // WRITE_ONLY — скрыто на read
    "organization": { "typeId": 2001, "id": "...", "value": { ... }}
  },
  "_access": {
    "typeId": 1001,
    "fingerprint": "kJ...",
    "entries": [
      { "fieldId": 1010, "name": "displayName", "canRead": true,  "canWrite": true,  "writeOnce": false, "writeOnly": false },
      { "fieldId": 1011, "name": "email",       "canRead": false, "canWrite": false, "writeOnce": false, "writeOnly": false },
      { "fieldId": 1012, "name": "passwordHash","canRead": false, "canWrite": true,  "writeOnce": false, "writeOnly": true  },
      { "fieldId": 1014, "name": "organization","canRead": true,  "canWrite": false, "writeOnce": true,  "writeOnly": false }
    ]
  }
}
```

Если у конкретного instance'а через `ownAccess` урезано право `displayName` — поле появится в `_accessOverrides`:

```json
{
  "data": { ... },
  "_access": { ... },
  "_accessOverrides": {
    "displayName": { "canRead": true, "canWrite": false, "writeOnce": false, "writeOnly": false }
  }
}
```

---
## 20. MapStruct DTO канал — параллельный JSON-каналу (требование 13)

### 20.1. Зачем отдельный канал

JSON-канал (`accessAwareObjectMapper`) хорош для `AccessProjected`-endpoint'ов — он автоматически скрывает поля. Но в типизированной DTO-логике (валидация, контракты, mapper'ы между bounded context'ами) JSON неудобен. Нужен **параллельный** канал на основе MapStruct, который:
- генерирует compile-time-проверяемый код mapping'а;
- применяет access-rules **симметрично** JSON-каналу: на outbound — скрывает поля для пользователя, на inbound — отказывает в записи;
- использует тот же `AccessContext`, тот же `AccessResolver`, ту же `MetadataSnapshot`.

### 20.2. `MappingContext` — явная передача контекста

Вместо ThreadLocal'а — `MappingContext` передаётся через MapStruct `@Context`-параметр. Совместим с virtual threads, не требует cleanup'а.

```java
public final class MappingContext {

    public enum Direction { INBOUND_DTO_TO_AGG, OUTBOUND_AGG_TO_DTO }

    public final AccessContext access;
    public final Direction direction;
    public final boolean strict;          // если true — бросаем при попытке записи запрещённого поля

    /** Для INBOUND: snapshot целевого aggregate'а, сделанный ДО mergeInto, для diff-сравнения. */
    private final IdentityHashMap<Object, Object> originalSnapshots = new IdentityHashMap<>();

    public MappingContext(AccessContext access, Direction direction, boolean strict) {
        this.access    = access;
        this.direction = direction;
        this.strict    = strict;
    }

    public static MappingContext outbound(AccessContext ctx) {
        return new MappingContext(ctx, Direction.OUTBOUND_AGG_TO_DTO, false);
    }
    public static MappingContext inbound(AccessContext ctx) {
        return new MappingContext(ctx, Direction.INBOUND_DTO_TO_AGG, true);
    }

    public void rememberOriginal(Object target, Object snapshot) {
        originalSnapshots.put(target, snapshot);
    }

    public Object originalOf(Object target) { return originalSnapshots.get(target); }
}
```

### 20.3. `AccessAwareMapper<A,D>` интерфейс

```java
public interface AccessAwareMapper<A extends AbstractAggregate<?>, D> {
    /** Aggregate → DTO. Скрывает HIDDEN/WRITE_ONLY поля. */
    D toDto(A aggregate, @Context MappingContext ctx);

    /** DTO → новый aggregate. Только для CREATE-сценариев. */
    A toNewAggregate(D dto, @Context MappingContext ctx);

    /**
     * Применяет DTO к существующему aggregate'у. БРОСАЕТ AccessDeniedException
     * при попытке изменить запрещённое поле (write_once на непустом, hidden, и т.д.).
     */
    void mergeInto(D dto, @MappingTarget A target, @Context MappingContext ctx);
}
```

### 20.4. `AccessAwareAfterMapping` — отдельный `@Component`, подключаемый через `uses=`

`@MapperConfig` интерфейсы с default-методами `@BeforeMapping`/`@AfterMapping` — **не** генерируют вызов в `MapperImpl`. Поэтому хуки выносятся в отдельный Spring-`@Component`, который mapper'ы перечисляют через `uses = {AccessAwareAfterMapping.class}`. MapStruct гарантированно генерирует поле `private final AccessAwareAfterMapping accessAware = ...` и вызывает его методы перед/после mapping'а.

```java
@Component
@RequiredArgsConstructor
public class AccessAwareAfterMapping {

    @Qualifier("outboxObjectMapper")
    private final ObjectMapper outboxMapper;
    private final AccessAwareMappingHelper helper;

    /**
     * Снимает «глубокий» snapshot целевого aggregate'а ПЕРЕД mergeInto через JSON round-trip
     * выделенным outboxObjectMapper'ом (без AccessAwareSerializerModifier — сохраняем все поля).
     * Корректно работает для коллекций (@ElementCollection), embedded'ов и nested ref-полей.
     */
    @BeforeMapping
    public <A extends AbstractAggregate<?>> void rememberOriginal(
            @MappingTarget A target, @Context MappingContext ctx) {
        if (ctx.direction != MappingContext.Direction.INBOUND_DTO_TO_AGG) return;
        try {
            byte[] bytes = outboxMapper.writeValueAsBytes(target);
            Object originalCopy = outboxMapper.readValue(bytes, target.getClass());
            ctx.rememberOriginal(target, originalCopy);
        } catch (IOException e) {
            throw new IllegalStateException(
                "Failed to snapshot aggregate " + target.getClass().getSimpleName() +
                " for inbound mapping", e);
        }
    }

    /** Включается на OUTBOUND: пост-обработка DTO с маскированием полей под текущий ctx. */
    @AfterMapping
    public <A extends AbstractAggregate<?>, D> void applyOutbound(
            @MappingTarget D dto, A source, @Context MappingContext ctx) {
        if (ctx.direction != MappingContext.Direction.OUTBOUND_AGG_TO_DTO) return;
        helper.applyOutbound(dto, source, ctx);
    }

    /**
     * Включается на INBOUND: проверяет каждое изменённое поле через AccessResolver и
     * либо допускает изменение, либо БРОСАЕТ AccessDeniedException (strict-режим).
     */
    @AfterMapping
    public <A extends AbstractAggregate<?>, D> void applyInbound(
            D dto, @MappingTarget A target, @Context MappingContext ctx) {
        if (ctx.direction != MappingContext.Direction.INBOUND_DTO_TO_AGG) return;
        Object original = ctx.originalOf(target);
        helper.applyInbound(dto, target, original, ctx);
    }
}
```

### 20.5. `AccessAwareMappingHelper` — outbound-маскирование и inbound-сверка

Обе ветки используют **deep snapshot через JSON round-trip** для корректного сравнения коллекций/embedded'ов. `AccessMetric.equals` (см. §5.1) теперь корректен — `Objects.equals(originalEmbedded, currentEmbedded)` не даёт ложных срабатываний.

```java
@Component
@RequiredArgsConstructor
public class AccessAwareMappingHelper {

    private final AccessResolver resolver;
    private final MetadataSnapshotProvider snapshots;

    /**
     * OUTBOUND: после генерации DTO обнуляем поля, для которых у пользователя нет canRead.
     * Поля DTO ищем по @FieldId (если DTO унаследован от same hierarchy)
     * либо по @AccessFieldRef-аннотации.
     */
    public <A extends AbstractAggregate<?>, D> void applyOutbound(D dto, A source, MappingContext ctx) {
        long typeId = snapshots.get().typeIdOf(source.getClass());
        for (DtoFieldRef ref : DtoFieldIntrospector.fieldsOf(dto.getClass())) {
            AccessLevel lvl = resolver.resolve(typeId, ref.fieldId(), source, ctx.access);
            if (!lvl.canRead()) {
                ref.write(dto, null);
            }
        }
    }

    /**
     * INBOUND: сравниваем поля DTO с original-snapshot (deep-cloned через JSON round-trip).
     * Если поле изменилось — проверяем canWrite(currentValue). Если нет права — strict-режим бросает,
     * non-strict — откатывает изменение.
     */
    public <A extends AbstractAggregate<?>, D> void applyInbound(
            D dto, A target, Object originalUntyped, MappingContext ctx) {
        if (originalUntyped == null) {
            // BeforeMapping не сработал (не наш случай) — fail-fast в strict
            if (ctx.strict) throw new IllegalStateException(
                "INBOUND mapping without original snapshot");
            return;
        }
        @SuppressWarnings("unchecked") A original = (A) originalUntyped;

        long typeId = snapshots.get().typeIdOf(target.getClass());
        AggregateDescriptor desc = snapshots.get().aggregate(typeId);

        for (DtoFieldRef ref : DtoFieldIntrospector.fieldsOf(dto.getClass())) {
            FieldDescriptor fd = desc.field(ref.fieldId());
            if (fd == null) continue;

            Object originalVal = fd.read(original);
            Object currentVal  = fd.read(target);
            // currentVal уже изменён MapStruct'ом (mergeInto завершился до AfterMapping).
            if (Objects.equals(originalVal, currentVal)) continue;

            AccessLevel lvl = resolver.resolve(typeId, fd.fieldId(), target, ctx.access);
            if (!lvl.canWrite(originalVal)) {
                if (ctx.strict) {
                    throw new AccessDeniedException(
                        "Field " + fd.name() + " (id=" + fd.fieldId() +
                        ") not writable for current AccessContext" +
                        (lvl.mode() == WriteMode.MODIFY_EMPTY ? " (init-once, already set)" : ""));
                }
                // non-strict — откатываем
                fd.write(target, originalVal);
            }
        }
    }
}
```

`DtoFieldIntrospector` — кеширующий резолвер `@AccessFieldRef`/`@FieldId` на DTO-полях:

```java
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AccessFieldRef {
    /** Какой fieldId в агрегате соответствует этому DTO-полю. */
    long value();
}

public final class DtoFieldIntrospector {

    private static final ConcurrentHashMap<Class<?>, List<DtoFieldRef>> CACHE = new ConcurrentHashMap<>();

    public static List<DtoFieldRef> fieldsOf(Class<?> dtoClass) {
        return CACHE.computeIfAbsent(dtoClass, DtoFieldIntrospector::scan);
    }

    private static List<DtoFieldRef> scan(Class<?> dtoClass) {
        List<DtoFieldRef> out = new ArrayList<>();
        for (Field f : dtoClass.getDeclaredFields()) {
            AccessFieldRef ref = f.getAnnotation(AccessFieldRef.class);
            FieldId fid = f.getAnnotation(FieldId.class);
            if (ref == null && fid == null) continue;
            long fieldId = (ref != null) ? ref.value() : fid.value();
            f.setAccessible(true);
            out.add(new DtoFieldRef(fieldId, f));
        }
        return List.copyOf(out);
    }
}

public record DtoFieldRef(long fieldId, Field field) {
    public Object read(Object dto) {
        try { return field.get(dto); } catch (IllegalAccessException e) { throw new IllegalStateException(e); }
    }
    public void write(Object dto, Object value) {
        try { field.set(dto, value); } catch (IllegalAccessException e) { throw new IllegalStateException(e); }
    }
}
```

### 20.6. Конкретный mapper

```java
@Mapper(
    config = MapStructCommonConfig.class,
    uses = {AggregateReferenceMapper.class, AccessAwareAfterMapping.class},
    componentModel = MappingConstants.ComponentModel.SPRING
)
public interface CustomerMapper extends AccessAwareMapper<CustomerAggregate, CustomerDto> {

    @Override
    @Mapping(target = "id",            source = "id")
    @Mapping(target = "displayName",   source = "displayName")
    @Mapping(target = "email",         source = "email")
    @Mapping(target = "passwordHash",  ignore = true)         // никогда не выходит в DTO
    @Mapping(target = "organizationId", source = "organization")
    CustomerDto toDto(CustomerAggregate src, @Context MappingContext ctx);

    @Override
    @Mapping(target = "id",           ignore = true)          // generated
    @Mapping(target = "version",      ignore = true)
    @Mapping(target = "createdAt",    ignore = true)
    @Mapping(target = "createdBy",    ignore = true)
    @Mapping(target = "updatedAt",    ignore = true)
    @Mapping(target = "updatedBy",    ignore = true)
    @Mapping(target = "ownAccess",    ignore = true)
    @Mapping(target = "passwordHash", source = "passwordHash")
    @Mapping(target = "organization", source = "organizationId")
    CustomerAggregate toNewAggregate(CustomerDto dto, @Context MappingContext ctx);

    @Override
    @Mapping(target = "id",           ignore = true)
    @Mapping(target = "version",      ignore = true)
    @Mapping(target = "createdAt",    ignore = true)
    @Mapping(target = "createdBy",    ignore = true)
    @Mapping(target = "updatedAt",    ignore = true)
    @Mapping(target = "updatedBy",    ignore = true)
    @Mapping(target = "ownAccess",    ignore = true)
    @Mapping(target = "organization", source = "organizationId")
    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    void mergeInto(CustomerDto dto, @MappingTarget CustomerAggregate target,
                   @Context MappingContext ctx);
}

@MapperConfig(componentModel = MappingConstants.ComponentModel.SPRING)
public interface MapStructCommonConfig {}
```

DTO с `@AccessFieldRef`:

```java
public class CustomerDto {
    @AccessFieldRef(1000) private UUID id;
    @AccessFieldRef(1010) private String displayName;
    @AccessFieldRef(1011) private String email;
    @AccessFieldRef(1012) private String passwordHash;
    @AccessFieldRef(1014) private UUID organizationId;
    // getters/setters
}
```

### 20.7. `AggregateReferenceMapper` — DTO-friendly преобразование

```java
@Component
@RequiredArgsConstructor
public class AggregateReferenceMapper {

    private final AggregateReferenceFactory refs;

    public UUID toUuid(AggregateReference<?, UUID> ref) {
        return ref == null ? null : (UUID) IdCodec.decode(ref.targetIdRaw(), UUID.class);
    }

    public AggregateReference<OrganizationAggregate, UUID> fromUuid(UUID id) {
        return id == null ? null : refs.of(OrganizationAggregate.class, id);
    }
}
```

### 20.8. Эквивалентность JSON ↔ MapStruct — обязательный тест

```java
@SpringBootTest
class CustomerMapperEquivalenceTest {

    @Autowired private CustomerMapper mapper;
    @Autowired @Qualifier("accessAwareObjectMapper") private ObjectMapper jsonMapper;
    @Autowired @Qualifier("dtoObjectMapper") private ObjectMapper dtoMapper;

    @ParameterizedTest
    @MethodSource("accessScenarios")
    void mapstruct_outbound_equivalent_to_json(AccessContext ctx) {
        CustomerAggregate agg = TestFixtures.customerWithFullData();

        try (var ignored = holder.bind(ctx)) {
            JsonNode jsonView = jsonMapper.valueToTree(agg);
            CustomerDto dto = mapper.toDto(agg, MappingContext.outbound(ctx));
            JsonNode dtoView = dtoMapper.valueToTree(dto);

            assertJsonEquivalent(jsonView, dtoView, "displayName", "email", "organizationId");
        }
    }

    static Stream<AccessContext> accessScenarios() {
        return Stream.of(
            TestFixtures.regularUserCtx(),
            TestFixtures.adminCtx(),
            TestFixtures.rootCtx(),
            TestFixtures.crossTenantCtx()
        );
    }
}
```

### 20.9. `MapperRegistry` — резолв `AccessAwareMapper<A,D>` по типу агрегата

```java
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1000)
public class MapperRegistry implements SmartInitializingSingleton {

    @Autowired private ApplicationContext appCtx;
    private final Map<Class<?>, AccessAwareMapper<?, ?>> mappersByAgg = new HashMap<>();

    @Override
    public void afterSingletonsInstantiated() {
        for (var bean : appCtx.getBeansOfType(AccessAwareMapper.class).values()) {
            ResolvableType t = ResolvableType.forClass(AccessAwareMapper.class, AopUtils.getTargetClass(bean));
            Class<?> aggClass = t.getGeneric(0).resolve();
            if (aggClass != null) mappersByAgg.put(aggClass, bean);
        }
    }

    @SuppressWarnings("unchecked")
    public <A extends AbstractAggregate<?>, D> AccessAwareMapper<A, D> forAggregate(Class<A> aggClass) {
        var m = (AccessAwareMapper<A, D>) mappersByAgg.get(aggClass);
        if (m == null) throw new IllegalStateException("No mapper for " + aggClass);
        return m;
    }
}
```

### 20.10. Использование в сервисе

```java
@Service
@RequiredArgsConstructor
public class CustomerService {

    private final CustomerRepository repo;
    private final CustomerMapper mapper;
    private final AccessContextHolder holder;
    private final Validator validator;

    @Transactional
    public CustomerDto updateFromDto(UUID id, CustomerDto dto) {
        var ctx = holder.getOrThrow();
        var agg = repo.findByIdLocked(id).orElseThrow();

        // 1. Структурная валидация DTO
        Set<ConstraintViolation<CustomerDto>> structDtoVs = validator.validate(dto);
        if (!structDtoVs.isEmpty()) throw new ConstraintViolationException(structDtoVs);

        // 2. Применение через MapStruct (бросит AccessDeniedException, если что не так)
        mapper.mergeInto(dto, agg, MappingContext.inbound(ctx));

        // 3. Структурная валидация итогового состояния агрегата (КРИТИЧНО — см. §3.3 / §15.1)
        Set<ConstraintViolation<CustomerAggregate>> aggVs = validator.validate(agg);
        if (!aggVs.isEmpty()) throw new ConstraintViolationException(aggVs);

        repo.save(agg);
        return mapper.toDto(agg, MappingContext.outbound(ctx));
    }

    public CustomerDto findOne(UUID id) {
        var ctx = holder.getOrThrow();
        var agg = repo.findById(id).orElseThrow();
        return mapper.toDto(agg, MappingContext.outbound(ctx));
    }
}
```

---
## 21. Кеширование (требование 4)

### 21.1. Spring Cache + Redis (без L1 для агрегатов; раздельные ObjectMapper'ы per-namespace)

**КРИТИЧНО (G2):** разные namespace'ы используют **разные** `ObjectMapper`'ы. `accessAwareObjectMapper` имеет `BeanSerializerModifier`, маскирующий поля по текущему `AccessContext`. Если использовать его для **всех** namespace'ов (как в v8), то raw-`aggregate`/`dto`/`page`/`mapping-plan` ключи будут содержать **отмаскированные** значения в зависимости от того, кто первый записал в кеш — это data-corruption (cross-user data leak: пользователь A записал агрегат с email, пользователь B без права на email перезаписал тот же ключ — A получит вид без email из «своего же» кеша).

**Правило:**
- `outboxObjectMapper` (raw, без modifier'а) — для `aggregate`, `dto`, `page`, `mapping-plan`. Сохраняем «полный» вид агрегата; access-rules применяются на чтение/render.
- `accessAwareObjectMapper` — **только** для `projection`-namespace, потому что там cache key включает fingerprint (per-effective-bits плана), и каждая entry детерминированно соответствует «виду для пользователей с одинаковыми effective-уровнями» (либо per-(user, instance), если есть instance-ACL — см. §19.2).

```java
@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    public RedisCacheManager cacheManager(
            RedisConnectionFactory cf,
            @Qualifier("outboxObjectMapper")      ObjectMapper rawMapper,
            @Qualifier("accessAwareObjectMapper") ObjectMapper accessMapper,
            @Value("${app.cache.schema-version}") String version) {

        var rawSerializer    = new GenericJackson2JsonRedisSerializer(rawMapper);
        var accessSerializer = new GenericJackson2JsonRedisSerializer(accessMapper);

        var rawBase = RedisCacheConfiguration.defaultCacheConfig()
            .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
            .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(rawSerializer))
            .computePrefixWith(name -> "v" + version + ":" + name + ":");

        var accessBase = rawBase.serializeValuesWith(
            RedisSerializationContext.SerializationPair.fromSerializer(accessSerializer));

        return RedisCacheManager.builder(cf)
            .cacheDefaults(rawBase.entryTtl(Duration.ofMinutes(30)))
            // RAW namespace'ы — outboxObjectMapper:
            .withCacheConfiguration("aggregate",    rawBase   .entryTtl(Duration.ofHours(1)))
            .withCacheConfiguration("dto",          rawBase   .entryTtl(Duration.ofMinutes(15)))
            .withCacheConfiguration("page",         rawBase   .entryTtl(Duration.ofMinutes(5)))
            .withCacheConfiguration("mapping-plan", rawBase   .entryTtl(Duration.ofHours(24)))
            // PROJECTION namespace — accessAwareObjectMapper (per-fingerprint key, корректно):
            .withCacheConfiguration("projection",   accessBase.entryTtl(Duration.ofMinutes(30)))
            .build();
    }
}
```

**ArchUnit-правило:** запрещаем использование `accessAwareObjectMapper` напрямую в любом cache-провайдере, кроме `AccessProjectionCache`:

```java
@ArchTest
static final ArchRule access_aware_mapper_only_in_projection_cache =
    fields().that().areAnnotatedWith(Qualifier.class)
            .and().haveAnnotationOfClassWithValue(Qualifier.class, "accessAwareObjectMapper")
            .should().beDeclaredInClassesThat().resideInAnyPackage(
                "..core.web..", "..core.cache.AccessProjectionCache..");
```

**Контракт-тест на cross-user изоляцию `aggregate`-cache'а:**

```java
@SpringBootTest
class AggregateCacheCrossUserIT {
    @Autowired CustomerRepository repo;
    @Autowired AccessContextHolder holder;

    @Test
    void cacheValuesAreNotMaskedByCurrentUser() throws Exception {
        UUID id = createCustomerWithFullData();   // под admin

        // Пользователь A: read-only, не имеет права на email-поле
        try (var ignored = holder.bind(testCtxWithoutEmailAccess())) {
            CustomerAggregate viewA = repo.findById(id).orElseThrow();
            // Под masking-modifier'ом email бы был null, но мы НЕ применяем его в cache.
            // Тест проверяет, что raw-cache содержит email — masking происходит на render-стадии,
            // не на cache-write.
        }
        // Пользователь B: full-access. Должен видеть email после A-доступа в кеш-цикле.
        try (var ignored = holder.bind(testCtxFull())) {
            CustomerAggregate viewB = repo.findById(id).orElseThrow();
            assertThat(viewB.getEmail()).isNotNull();
        }
    }
}
```

### 21.2. Карта namespace'ов

| Cache | Использование | Ключ | TTL |
|---|---|---|---|
| `aggregate` | Raw-объекты НЕ-`@AccessFiltered`-агрегатов | `<typeId>:<idRaw>` | 1 час |
| `projection` | Rendered JSON view (per-typeId × fingerprint × instance) | `<typeId>:<idRaw>:<fingerprint>` | 30 мин |
| `dto` | DTO-обёртки | `<typeId>:<idRaw>:<dto-class>:<fingerprint>` | 15 мин |
| `page` | Page<T> для read-heavy endpoint'ов | `<typeId>:<query-hash>:<page>:<size>:<accessKeyHash>` | 5 мин |
| `mapping-plan` | Скомпилированные `AccessProjectionTemplate`-fingerprint'ы | `<typeId>:<accessKeyHash>` | 24 часа |

**Принцип:** raw-агрегатный кеш `aggregate` **не используется** для `@AccessFiltered`-типов. Page-cache содержит `accessKeyHash`, потому что **результат запроса** (какие строки попали) зависит от фильтра пользователя — это не projection-cache, а cross-user data leak risk без `accessKeyHash`. Projection-cache — наоборот, fingerprint-based: разные JWT с одинаковой baseline-разрешённостью полей шарят entries.

### 21.3. `CacheKeys`

```java
public final class CacheKeys {
    private CacheKeys() {}

    public static String aggregate(long typeId, String idRaw) {
        return typeId + ":" + idRaw;
    }
    public static String projection(long typeId, String idRaw, String fingerprint) {
        return typeId + ":" + idRaw + ":" + fingerprint;
    }
    public static String dto(long typeId, String idRaw, String dtoClass, String fingerprint) {
        return typeId + ":" + idRaw + ":" + dtoClass + ":" + fingerprint;
    }
    public static String page(long typeId, String queryHash, int page, int size, String accessKeyHash) {
        return typeId + ":" + queryHash + ":" + page + ":" + size + ":" + accessKeyHash;
    }
    public static String mappingPlan(long typeId, String accessKeyHash) {
        return typeId + ":" + accessKeyHash;
    }
}
```

### 21.4. Reverse-индексы для точечной инвалидации

Чтобы инвалидировать **именно те** page'ы и projection'ы, которые зависят от изменившейся instance — не используем `SCAN`. Поддерживаем reverse-индексы:

```sql
-- В Redis (как Sets)
page-deps:<pageKey> = { <typeId>:<idRaw>, ... }            -- какие entity участвовали
page-deps-by-type:<typeId>:<idRaw> = { <pageKey>, ... }    -- какие pageKey зависят от entity
ref-deps:<typeId>:<idRaw> = { <projectionKey>, ... }        -- projection'ы, ссылающиеся на entity
```

При записи в `page`-cache — атомарно (`MULTI`/`EXEC`) обновляем оба индекса:

```java
@Component
@RequiredArgsConstructor
public class PageCacheWriter {

    private final RedisTemplate<String, Object> redis;
    @Qualifier("accessAwareObjectMapper")
    private final ObjectMapper om;

    public void write(String pageKey, Object pageBody, Set<String> dependencyKeys) {
        redis.execute(new SessionCallback<Object>() {
            @SuppressWarnings({"rawtypes","unchecked"})
            @Override public Object execute(RedisOperations ops) throws DataAccessException {
                ops.multi();
                try {
                    ops.opsForValue().set("v1:page:" + pageKey, om.writeValueAsString(pageBody),
                                          Duration.ofMinutes(5));
                } catch (JsonProcessingException e) { throw new IllegalStateException(e); }
                if (!dependencyKeys.isEmpty()) {
                    ops.opsForSet().add("page-deps:" + pageKey, dependencyKeys.toArray(String[]::new));
                    ops.expire("page-deps:" + pageKey, Duration.ofHours(2));
                    for (String depKey : dependencyKeys) {
                        ops.opsForSet().add("page-deps-by-type:" + depKey, pageKey);
                        ops.expire("page-deps-by-type:" + depKey, Duration.ofHours(2));
                    }
                }
                return ops.exec();
            }
        });
    }
}
```

### 21.5. `CacheInvalidationListener` — полная схема

```java
@Component
@RequiredArgsConstructor
public class CacheInvalidationListener {

    private final RedisTemplate<String, Object> redis;
    private final MetadataSnapshotProvider snapshots;
    private final CaffeineUserAccessProvider userAccessCache;
    private final SystemAccessContexts systems;
    private final AccessContextHolder holder;

    /** Async по умолчанию — горячий путь не блокируется. */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onChanged(AggregateChangedEvent ev) {
        // Инвалидация делается под системным read-write — это служебная операция
        try (var ignored = holder.bind(systems.systemReadWrite())) {
            String typeId = String.valueOf(ev.aggregateTypeId());
            String idRaw  = ev.aggregateIdRaw();
            String depKey = typeId + ":" + idRaw;

            // 1. Aggregate raw-cache (только для не-filtered типов)
            var desc = snapshots.get().aggregate(ev.aggregateTypeId());
            if (!desc.isAccessFiltered()) {
                redis.delete("v1:aggregate:" + depKey);
            }

            // 2. Projection-cache — точечно через ref-deps
            Set<String> projKeys = redis.opsForSet().members("ref-deps:" + depKey);
            if (projKeys != null && !projKeys.isEmpty()) {
                redis.delete(projKeys.stream().map(k -> "v1:projection:" + k).toList());
                redis.delete("ref-deps:" + depKey);
            }

            // 3. DTO-cache — аналогично
            Set<String> dtoKeys = redis.opsForSet().members("dto-deps:" + depKey);
            if (dtoKeys != null && !dtoKeys.isEmpty()) {
                redis.delete(dtoKeys.stream().map(k -> "v1:dto:" + k).toList());
                redis.delete("dto-deps:" + depKey);
            }

            // 4. Page-cache — через reverse-индекс
            Set<String> pageKeys = redis.opsForSet().members("page-deps-by-type:" + depKey);
            if (pageKeys != null && !pageKeys.isEmpty()) {
                redis.delete(pageKeys.stream().map(k -> "v1:page:" + k).toList());
                for (String pageKey : pageKeys) redis.delete("page-deps:" + pageKey);
                redis.delete("page-deps-by-type:" + depKey);
            }

            // 5. UserAccessProvider Caffeine — если изменился UserAggregate
            if (ev.aggregateTypeId() == snapshots.get().typeIdOf(UserAggregate.class)) {
                userAccessCache.evictUser(typeId, idRaw);
            }
        }
    }
}
```

`@Async` гарантирует, что отказ Redis'а не блокирует commit. Для критичных кейсов (сценарии, где stale-read недопустим) — `@CacheInvalidationPolicy(async=false)` на агрегате; листенер проверит и сделает sync.

---

## 22. Pageable + AccessAwarePage (требование 12)

### 22.1. AccessAwarePage envelope

`Page<T>` от Spring Data **не учитывает** post-load filtering. Если `@PostLoadAccessCheck` отбросил какие-то instance из загруженной страницы (теоретически возможно при нештатных условиях, например, если фильтр упал из-за timing'а грантов), то `totalElements` будет неточным. Для стабильности UX используем **overfetch + filter**:

```java
public record AccessAwarePage<T>(
    List<T> content,
    Pageable pageable,
    long totalElements,
    int  effectivePageSize,
    boolean approximateTotal
) implements Page<T> {

    @Override public int getNumber()        { return pageable.getPageNumber(); }
    @Override public int getSize()          { return pageable.getPageSize(); }
    @Override public int getNumberOfElements(){ return content.size(); }
    @Override public List<T> getContent()   { return content; }
    @Override public boolean hasContent()   { return !content.isEmpty(); }
    @Override public Sort getSort()         { return pageable.getSort(); }
    @Override public boolean isFirst()      { return !hasPrevious(); }
    @Override public boolean isLast()       { return !hasNext(); }
    @Override public boolean hasNext()      { return getNumber() + 1 < getTotalPages(); }
    @Override public boolean hasPrevious()  { return getNumber() > 0; }
    @Override public Pageable nextPageable(){ return hasNext() ? pageable.next() : Pageable.unpaged(); }
    @Override public Pageable previousPageable() { return hasPrevious() ? pageable.previousOrFirst() : Pageable.unpaged(); }
    @Override public int getTotalPages()    { return getSize() == 0 ? 1 : (int) Math.ceil((double) totalElements / getSize()); }
    @Override public long getTotalElements(){ return totalElements; }
    @Override public <U> Page<U> map(Function<? super T, ? extends U> c) {
        return new AccessAwarePage<>(content.stream().map(c).toList(), pageable, totalElements, effectivePageSize, approximateTotal);
    }
    @Override public Iterator<T> iterator() { return content.iterator(); }
}
```

### 22.2. `FilteredCountQuery` — `CriteriaBuilder` с access-фильтрацией и `additionalSpec`

В v8 count выполнялся через native SQL и **игнорировал** `additionalSpec`. Это давало incorrect `totalElements` в любой пагинации с user-supplied Specification. v9 переписан на `CriteriaBuilder`: `additionalSpec` корректно учитывается, native SQL и связанная с ним SQL-identifier-валидация (regex'ом) исключены из hot-path'а.

```java
@Component
@RequiredArgsConstructor
public class FilteredCountQuery {

    @PersistenceContext
    private final EntityManager em;
    private final ClaimsExtractor claimsExtractor;
    private final UserAccessProvider userAccess;
    private final MetadataSnapshotProvider snapshots;

    @SuppressWarnings({"unchecked", "rawtypes"})
    public <T extends AbstractAggregate<?>> long countWithFilters(
            Class<T> entityClass,
            long typeId,
            AccessContext ctx,
            @Nullable Specification<T> additionalSpec) {

        var snap = snapshots.get();
        var desc = snap.aggregate(typeId);
        var filters = snap.filtersForTypeId(typeId);
        AccessMetric um = userAccess.metricFor(ctx);

        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<T> root = cq.from(entityClass);
        cq.select(cb.count(root));

        List<Predicate> preds = new ArrayList<>();

        // Soft-delete: Hibernate-native @SoftDelete добавляет предикат сам, но в Criteria
        // он иногда игнорируется (зависит от версии). Дублируем явно для защиты.
        if (desc.softDelete()) {
            preds.add(cb.isFalse(root.get("deleted")));
        }

        // Access-filters — построение через FieldDescriptor.propertyName(), без native SQL и regex'а.
        for (AccessFilterDef f : filters) {
            if (canBypass(um, f)) continue;
            Set<String> claimValues = claimsExtractor.extract(ctx.auth(), f.userClaim().jwtName);
            if (claimValues.isEmpty()) return 0L;

            FieldDescriptor refField = desc.field(f.fieldId());
            // refField — AggregateReference-поле; путь до колонки id-цели:
            //   root.get(<propertyName>).get("targetIdRaw")
            Path<Object> targetIdPath = root.get(refField.propertyName()).get("targetIdRaw");

            Class<?> paramType = snap.idClassByTypeId(f.referencedTypeId());
            List<Object> typedIds = claimValues.stream()
                .map(s -> IdCodec.decode(s, (Class<? extends Serializable>) paramType))
                .collect(Collectors.toList());

            preds.add(targetIdPath.in(typedIds));
        }

        // additionalSpec — теперь УЧИТЫВАЕТСЯ
        if (additionalSpec != null) {
            Predicate p = additionalSpec.toPredicate(root, cq, cb);
            if (p != null) preds.add(p);
        }

        if (!preds.isEmpty()) cq.where(cb.and(preds.toArray(new Predicate[0])));

        return em.createQuery(cq).getSingleResult();
    }

    private boolean canBypass(AccessMetric um, AccessFilterDef f) {
        int bypassMask = AccessFlags.ADMIN_READ | AccessFlags.ADMIN_WRITE
                       | AccessFlags.ROOT_READ  | AccessFlags.ROOT_WRITE;
        int g = AccessFlags.expand(um.globalFlags());
        if ((g & bypassMask) != 0) return true;
        int t = AccessFlags.expand(um.typeFlags().getOrDefault(f.referencedTypeId(), 0));
        if ((t & bypassMask) != 0) return true;
        for (long tid : f.transitiveBypassTypeIds()) {
            int tf = AccessFlags.expand(um.typeFlags().getOrDefault(tid, 0));
            if ((tf & bypassMask) != 0) return true;
        }
        return false;
    }
}
```

> **G5:** SQL-identifier-валидация имён `filterField`/`filterColumn`/`tableName` остаётся в `BootstrapValidator` как defense-in-depth (см. §24.7) — но в hot-path'е она больше не нужна, потому что `CriteriaBuilder` оперирует JPA-метамоделью, а не строками. Bootstrap-валидация защищает производные использования (например, native-SQL в `SoftDeleteAwareReader`, см. §23).

### 22.3. `StableChunkPageRenderer` — overfetch + filter (с интеграцией `MARK_AND_DROP`)

```java
@Service
@RequiredArgsConstructor
public class StableChunkPageRenderer {

    private final FilteredCountQuery counter;
    private final MetadataSnapshotProvider snapshots;
    private final ObjectProvider<PostLoadDropMarker> dropMarkerProvider;

    @SuppressWarnings({"unchecked", "rawtypes"})
    public <T extends AbstractAggregate<?>> AccessAwarePage<T> render(
            AggregateRepository<T, ?> repo,
            Specification<T> spec, Pageable pg, AccessContext ctx) {

        long typeId = typeIdOf(repo);
        Class<T> entityClass = (Class<T>) snapshots.get().aggregate(typeId).javaClass();

        long total = counter.countWithFilters(entityClass, typeId, ctx, spec);

        // Overfetch на 50% — учитывая возможное усечение MARK_AND_DROP'ом
        int overfetch = (int) Math.min(pg.getPageSize() * 1.5, pg.getPageSize() + 50L);
        Pageable expanded = PageRequest.of(pg.getPageNumber(), overfetch, pg.getSort());

        // findAll проходит через AOP, который выставит PostLoadMode.MARK_AND_DROP.
        // PostLoadAccessCheckListener пометит запрещённые entities в PostLoadDropMarker, не бросив.
        Page<T> fat = repo.findAll(spec, expanded);

        PostLoadDropMarker marker = dropMarkerProvider.getIfAvailable();
        List<T> visible = (marker == null)
            ? fat.getContent()
            : fat.getContent().stream().filter(o -> !marker.isMarked(o)).toList();

        // Обрезаем до запрошенного размера
        List<T> trimmed = visible.stream().limit(pg.getPageSize()).toList();
        boolean approximate = (marker != null && marker.hadAnyDrops())
                           || (visible.size() < fat.getContent().size());

        return new AccessAwarePage<>(trimmed, pg, total, trimmed.size(), approximate);
    }

    private long typeIdOf(AggregateRepository<?, ?> r) {
        return snapshots.get().typeIdOf(((Class<?>) ((Repositories) r).getDomainType()));
    }
}
```

> **G4:** `approximateTotal=true` теперь корректно выставляется при любых усечениях post-load. UI получает явный сигнал, что фактическое число строк может быть меньше `totalElements` (например, из-за гонки между загрузкой и недавним grant'ом).

### 22.4. `AccessAwarePageRepository` — обёртка для типичного API

Чтобы сервис не вызывал `StableChunkPageRenderer` напрямую, добавляется default-метод в `AggregateRepository`:

```java
@NoRepositoryBean
@AggregateRepository
public interface AggregateRepository<T extends AbstractAggregate<ID>, ID extends Serializable>
        extends JpaRepository<T, ID>, JpaSpecificationExecutor<T> {

    Optional<T> findByIdLocked(ID id);

    /** Возвращает AccessAwarePage с access-aware count'ом и MARK_AND_DROP-фильтрацией. */
    default AccessAwarePage<T> findAllAccessAware(Specification<T> spec, Pageable pg, AccessContext ctx) {
        return AggregateRepositorySupport.renderer().render(this, spec, pg, ctx);
    }
}

/** Helper-bridge для default-метода: предоставляет StableChunkPageRenderer. */
@Component
public final class AggregateRepositorySupport {
    private static volatile StableChunkPageRenderer RENDERER;

    @PostConstruct void init(@Autowired StableChunkPageRenderer r) { RENDERER = r; }
    public static StableChunkPageRenderer renderer() {
        var r = RENDERER;
        if (r == null) throw new IllegalStateException("AggregateRepositorySupport not initialized");
        return r;
    }
}
```

### 22.5. `AccessAwareReportingService` — агрегационные запросы (G10)

`@AccessFiltered` через Hibernate `@Filter` применяется к JPQL `SELECT`, но:
- При `SELECT SUM(...) GROUP BY ...` фильтр по идее активен, но через native SQL / `JdbcTemplate` / Spring Data Projections он **не** активирован.
- Agg-функции через `JpaSpecificationExecutor` не имеют простого способа собрать `totalElements` с access-предикатами.

Сервис `AccessAwareReportingService` строит агрегации через тот же `CriteriaBuilder`-путь, что и `FilteredCountQuery` — гарантируя, что access-предикаты применяются:

```java
@Component
@RequiredArgsConstructor
public class AccessAwareReportingService {

    @PersistenceContext
    private final EntityManager em;
    private final ClaimsExtractor claimsExtractor;
    private final UserAccessProvider userAccess;
    private final MetadataSnapshotProvider snapshots;

    /**
     * Универсальный агрегационный запрос с access-фильтрами и опциональным additionalSpec.
     *
     * @param entityClass     класс агрегата
     * @param typeId          typeId агрегата
     * @param ctx             текущий access-context
     * @param queryBuilder    функция, строящая select/groupBy/orderBy на основе CriteriaBuilder + Root
     * @param additionalSpec  опциональный пользовательский spec (предикаты where)
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public <T extends AbstractAggregate<?>, R> List<R> aggregate(
            Class<T> entityClass,
            long typeId,
            AccessContext ctx,
            ReportQueryBuilder<T, R> queryBuilder,
            @Nullable Specification<T> additionalSpec) {

        var snap = snapshots.get();
        var filters = snap.filtersForTypeId(typeId);
        AccessMetric um = userAccess.metricFor(ctx);

        CriteriaBuilder cb = em.getCriteriaBuilder();
        // queryBuilder сам выбирает Class<R> и оформляет CriteriaQuery
        CriteriaQuery<R> cq = queryBuilder.createQuery(cb);
        Root<T> root = cq.from(entityClass);
        queryBuilder.configureQuery(cq, root, cb);

        List<Predicate> preds = new ArrayList<>();

        var desc = snap.aggregate(typeId);
        if (desc.softDelete()) preds.add(cb.isFalse(root.get("deleted")));

        // Access-filters — те же, что и в FilteredCountQuery
        for (AccessFilterDef f : filters) {
            if (canBypass(um, f)) continue;
            Set<String> claimValues = claimsExtractor.extract(ctx.auth(), f.userClaim().jwtName);
            if (claimValues.isEmpty()) return List.of();

            FieldDescriptor refField = desc.field(f.fieldId());
            Path<Object> targetIdPath = root.get(refField.propertyName()).get("targetIdRaw");

            Class<?> paramType = snap.idClassByTypeId(f.referencedTypeId());
            List<Object> typedIds = claimValues.stream()
                .map(s -> IdCodec.decode(s, (Class<? extends Serializable>) paramType))
                .toList();
            preds.add(targetIdPath.in(typedIds));
        }

        if (additionalSpec != null) {
            Predicate p = additionalSpec.toPredicate(root, cq, cb);
            if (p != null) preds.add(p);
        }

        if (!preds.isEmpty()) {
            // Дополнительный where: либо у queryBuilder уже есть свой where (тогда AND'им),
            // либо ставим свой:
            Predicate existing = cq.getRestriction();
            cq.where(existing == null ? cb.and(preds.toArray(new Predicate[0]))
                                       : cb.and(existing, cb.and(preds.toArray(new Predicate[0]))));
        }

        return em.createQuery(cq).getResultList();
    }

    public interface ReportQueryBuilder<T, R> {
        CriteriaQuery<R> createQuery(CriteriaBuilder cb);                 // cb.createQuery(R.class)
        void configureQuery(CriteriaQuery<R> cq, Root<T> root, CriteriaBuilder cb);
    }

    private boolean canBypass(AccessMetric um, AccessFilterDef f) {
        int bypassMask = AccessFlags.ADMIN_READ | AccessFlags.ADMIN_WRITE
                       | AccessFlags.ROOT_READ  | AccessFlags.ROOT_WRITE;
        int g = AccessFlags.expand(um.globalFlags());
        if ((g & bypassMask) != 0) return true;
        int t = AccessFlags.expand(um.typeFlags().getOrDefault(f.referencedTypeId(), 0));
        if ((t & bypassMask) != 0) return true;
        for (long tid : f.transitiveBypassTypeIds()) {
            int tf = AccessFlags.expand(um.typeFlags().getOrDefault(tid, 0));
            if ((tf & bypassMask) != 0) return true;
        }
        return false;
    }
}
```

#### Пример использования: «сумма заказов по организациям»

```java
public record OrderTotalByOrg(UUID orgId, BigDecimal total, long count) {}

@Service
@RequiredArgsConstructor
public class OrderReportService {
    private final AccessAwareReportingService reporting;
    private final AccessContextHolder holder;

    public List<OrderTotalByOrg> totalsByOrg(@Nullable Specification<OrderAggregate> userSpec) {
        var ctx = holder.getOrThrow();
        long typeId = 2001;     // OrderAggregate

        return reporting.aggregate(OrderAggregate.class, typeId, ctx, new ReportQueryBuilder<>() {
            @Override public CriteriaQuery<OrderTotalByOrg> createQuery(CriteriaBuilder cb) {
                return cb.createQuery(OrderTotalByOrg.class);
            }
            @Override public void configureQuery(CriteriaQuery<OrderTotalByOrg> cq,
                                                  Root<OrderAggregate> root, CriteriaBuilder cb) {
                Path<UUID> orgId = root.get("organization").get("targetIdRaw");
                cq.select(cb.construct(OrderTotalByOrg.class,
                    orgId,
                    cb.sum(root.get("amount")),
                    cb.count(root)));
                cq.groupBy(orgId);
                cq.orderBy(cb.desc(cb.sum(root.get("amount"))));
            }
        }, userSpec);
    }
}
```

#### ArchUnit-правила

```java
@ArchTest
static final ArchRule no_native_aggregation_on_filtered_aggregates =
    noClasses().that().resideInAnyPackage("..domain.service..", "..domain.report..")
               .should().callMethod(EntityManager.class, "createNativeQuery", String.class)
               .orShould().callMethod(JdbcTemplate.class, "queryForList", String.class, Object[].class);

@ArchTest
static final ArchRule reporting_must_use_AccessAwareReportingService =
    classes().that().areAnnotatedWith(Service.class)
             .and().haveSimpleNameContaining("Report")
             .should().dependOnClassesThat().areAssignableTo(AccessAwareReportingService.class);
```

Любые reporting-сервисы обязаны идти через `AccessAwareReportingService` — обходной путь (native SQL, JdbcTemplate) запрещён ArchUnit'ом. Это закрывает существенную дыру: «доступ через reporting», которая в v8 не была освещена.

---

## 23. Soft-delete через Hibernate-native + ROOT_READ override

### 23.1. Native поддержка

Hibernate 6.4+ имеет встроенный `@org.hibernate.annotations.SoftDelete`. Используем его:

```java
@Entity
@TypeId(1001)
@org.hibernate.annotations.SoftDelete(columnName = "deleted")
public class CustomerAggregate extends AbstractAggregate<UUID> { ... }
```

Hibernate автоматически:
- добавляет `deleted = false` в SELECT'ы;
- переписывает `DELETE` в `UPDATE ... SET deleted = true`;
- `@Where`-предикаты не нужны.

### 23.2. ROOT_READ override через `SoftDeleteAwareReader`

Иногда нужен view, включающий удалённые (audit/admin endpoint'ы). Hibernate 6.4+ позволяет программно отключать soft-delete через `Session.unwrap(SessionImplementor.class).getLoadQueryInfluencers()`, но более чистый путь — отдельный read-сервис, делающий native query без SoftDelete:

```java
@Service
@RequiredArgsConstructor
public class SoftDeleteAwareReader {

    @PersistenceContext
    private final EntityManager em;
    private final AccessContextHolder holder;
    private final UserAccessProvider userAccess;
    private final MetadataSnapshotProvider snapshots;

    public <T extends AbstractAggregate<ID>, ID extends Serializable>
           Optional<T> findIncludingDeleted(Class<T> aggClass, ID id) {
        var ctx = holder.getOrThrow();
        var um  = userAccess.metricFor(ctx);
        long typeId = snapshots.get().typeIdOf(aggClass);

        int g = AccessFlags.expand(um.globalFlags());
        int t = AccessFlags.expand(um.typeFlags().getOrDefault(typeId, 0));
        boolean rootRead = ((g & AccessFlags.ROOT_READ) != 0)
                        || ((t & AccessFlags.ROOT_READ) != 0);
        if (!rootRead) {
            throw new AccessDeniedException(
                "findIncludingDeleted requires ROOT_READ for typeId=" + typeId);
        }

        String tableName = snapshots.get().aggregate(typeId).tableName();
        @SuppressWarnings("unchecked")
        T result = (T) em.createNativeQuery(
                "SELECT * FROM " + tableName + " WHERE id = :id", aggClass)
            .setParameter("id", id)
            .getResultStream()
            .findFirst()
            .orElse(null);
        return Optional.ofNullable(result);
    }
}
```

### 23.3. Полный жизненный цикл

```
1. Soft-deleted: Hibernate update deleted=true → fires @PreUpdate → AccessAwarePreUpdateListener
   проверяет, что у пользователя есть canWrite (DELETE для агрегата = WRITE на flag deleted),
   плюс @DomainCallback(kind=SOFT_DELETE).
2. После TTL-окна (например, 30 дней) — отдельный `HardDeletePurgeJob` физически удаляет
   старые soft-deleted строки native UPDATE'ом + хук на cleanup ref-deps Redis-индексов.
```

### 23.4. `HardDeletePurgeJob`

```java
@Component
@RequiredArgsConstructor
public class HardDeletePurgeJob {

    @PersistenceContext
    private final EntityManager em;
    private final SystemAccessContexts systems;
    private final AccessContextHolder holder;
    private final MetadataSnapshotProvider snapshots;

    @Scheduled(cron = "0 0 4 * * *")     // 4:00 ежедневно
    @Transactional
    public void purge() {
        try (var ignored = holder.bind(systems.maxPrivileges())) {
            Instant cutoff = Instant.now().minus(Duration.ofDays(30));
            for (var desc : snapshots.get().allAggregates()) {
                if (!desc.softDelete()) continue;
                String table = desc.tableName();
                int n = em.createNativeQuery(
                        "DELETE FROM " + table +
                        " WHERE deleted = true AND updated_at < :cutoff")
                    .setParameter("cutoff", cutoff)
                    .executeUpdate();
                if (n > 0) log.info("Purged {} hard-deleted rows from {}", n, table);
            }
        }
    }
}
```

---
## 24. Bootstrap: запекание метаданных (требование 11)

### 24.1. `MetadataSnapshot` — единая иммутабельная структура

```java
public final class MetadataSnapshot {

    private final Map<Long, AggregateDescriptor> aggregatesByTypeId;
    private final Map<Class<?>, Long>            typeIdByClass;
    private final Map<Long, Class<? extends Serializable>> idClassByTypeId;
    private final Map<Long, List<AccessFilterDef>> filtersByTypeId;
    private final GlobalGrants globalGrants;
    private final AccessFilterGraph filterGraph;
    private final Instant builtAt;

    // ... полная реализация конструктора + accessors

    public AggregateDescriptor aggregate(long typeId) {
        var d = aggregatesByTypeId.get(typeId);
        if (d == null) throw new IllegalStateException("No descriptor for typeId=" + typeId);
        return d;
    }

    public long typeIdOf(Class<?> cls) {
        Long t = typeIdByClass.get(cls);
        if (t == null) throw new IllegalStateException(
            "Class " + cls.getName() + " is not annotated with @TypeId or not registered");
        return t;
    }

    public Class<? extends Serializable> idClassByTypeId(long typeId) {
        var c = idClassByTypeId.get(typeId);
        if (c == null) throw new IllegalStateException("No id class for typeId=" + typeId);
        return c;
    }

    public List<AccessFilterDef> filtersForTypeId(long typeId) {
        return filtersByTypeId.getOrDefault(typeId, List.of());
    }

    public Collection<AggregateDescriptor> allAggregates() { return aggregatesByTypeId.values(); }
    public GlobalGrants globalGrants() { return globalGrants; }
    public AccessFilterGraph filterGraph() { return filterGraph; }
    public Instant builtAt() { return builtAt; }
}
```

### 24.2. `AggregateDescriptor`

```java
public final class AggregateDescriptor {

    private final long typeId;
    private final Class<?> javaClass;
    private final String tableName;
    private final Class<? extends Serializable> idClass;
    private final boolean softDelete;
    private final boolean accessFiltered;
    private final AccessLevel defaultRepoAccess;
    private final int prefetchDepth;
    private final List<FieldDescriptor> fields;                          // flat tree, рекурсивно
    private final Map<Long, FieldDescriptor>   fieldByFieldId;
    private final Map<String, FieldDescriptor> fieldByPropertyName;
    private final Map<String, FieldDescriptor> fieldByShortName;

    public long typeId() { return typeId; }
    public Class<?> javaClass() { return javaClass; }
    public String tableName() { return tableName; }
    public Class<? extends Serializable> idClass() { return idClass; }
    public boolean softDelete() { return softDelete; }
    public boolean isAccessFiltered() { return accessFiltered; }
    public AccessLevel defaultRepoAccess() { return defaultRepoAccess; }
    public int prefetchDepth() { return prefetchDepth; }
    public List<FieldDescriptor> fields() { return fields; }
    public FieldDescriptor field(long fieldId) { return fieldByFieldId.get(fieldId); }
    public FieldDescriptor fieldByPropertyName(String prop) { return fieldByPropertyName.get(prop); }
    public FieldDescriptor fieldByName(String name) { return fieldByShortName.get(name); }

    /**
     * Резолв FieldDescriptor по полному path'у от корня агрегата
     * (например, для embedded-в-embedded: 1013 → 10130004 → ipAddress).
     * Stack — родительские fieldId'ы; propertyName — имя текущего leaf-property.
     */
    public FieldDescriptor fieldByPath(Deque<Long> parentStack, String propertyName) {
        // path = parentStack (top→bottom) + propertyName
        for (FieldDescriptor fd : fields) {
            if (!fd.shortName().equals(propertyName)) continue;
            if (matchesStack(fd, parentStack)) return fd;
        }
        return null;
    }

    private boolean matchesStack(FieldDescriptor fd, Deque<Long> parentStack) {
        // FieldDescriptor хранит свою цепочку родителей parentChain (long[]).
        long[] expected = parentStack.stream().mapToLong(Long::longValue).toArray();
        // ВНИМАНИЕ: parentStack — это Deque в LIFO-порядке (push/pop), iterate сверху вниз.
        // Сравниваем длины и элементы.
        if (fd.parentChain().length != expected.length) return false;
        for (int i = 0; i < expected.length; i++) {
            if (fd.parentChain()[i] != expected[i]) return false;
        }
        return true;
    }
}
```

### 24.3. `FieldDescriptor` — хранит `Field` reference

`FieldDescriptor` хранит готовый `java.lang.reflect.Field` reference, делая `setAccessible(true)` один раз на bootstrap'е. Никаких повторных `findField` через рефлексию в горячем пути.

```java
public final class FieldDescriptor {

    private final long fieldId;
    private final String name;                 // fully-qualified path, например "cookies.security.ipAddress"
    private final String shortName;            // последний сегмент path'а ("ipAddress")
    private final String propertyName;         // hibernate property name (для PreUpdate state-array)
    private final long[] parentChain;          // цепочка fieldId'ов родителей от корня
    private final long parentFieldId;          // -1 для top-level; для embedded — fieldId родителя
    private final Class<?> javaType;
    private final Field rawField;              // готовый reflect.Field, setAccessible(true)
    private final AccessLevel defaultAccess;
    private final boolean isAggregateReference;
    private final long referencedTypeId;       // если AggregateReference, ≠ -1
    private final boolean isElementCollection; // G10: true для полей с @ElementCollection
    private final Class<?> elementType;        // G10: тип элемента коллекции, null для не-collection

    public FieldDescriptor(long fieldId, String name, String shortName, String propertyName,
                           long[] parentChain, long parentFieldId,
                           Class<?> javaType, Field rawField,
                           AccessLevel defaultAccess,
                           boolean isAggregateReference, long referencedTypeId,
                           boolean isElementCollection, Class<?> elementType) {
        this.fieldId = fieldId;
        this.name = name;
        this.shortName = shortName;
        this.propertyName = propertyName;
        this.parentChain = parentChain.clone();
        this.parentFieldId = parentFieldId;
        this.javaType = javaType;
        this.rawField = rawField;
        rawField.setAccessible(true);          // один раз на bootstrap'е
        this.defaultAccess = defaultAccess;
        this.isAggregateReference = isAggregateReference;
        this.referencedTypeId = referencedTypeId;
        this.isElementCollection = isElementCollection;
        this.elementType = elementType;
    }

    public long fieldId() { return fieldId; }
    public String name() { return name; }
    public String shortName() { return shortName; }
    public String propertyName() { return propertyName; }
    public long[] parentChain() { return parentChain.clone(); }
    public long parentFieldId() { return parentFieldId; }
    public Class<?> javaType() { return javaType; }
    public Field rawField() { return rawField; }
    public AccessLevel defaultAccess() { return defaultAccess; }
    public boolean isAggregateReference() { return isAggregateReference; }
    public long referencedTypeId() { return referencedTypeId; }
    public boolean isElementCollection() { return isElementCollection; }
    public Class<?> elementType() { return elementType; }

    /** Чтение значения из instance'а (для не-embedded полей — напрямую; для embedded — обход родителей). */
    public Object read(Object root) {
        try {
            Object container = walkParents(root);
            if (container == null) return null;
            return rawField.get(container);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Field read failed: " + name, e);
        }
    }

    public void write(Object root, Object value) {
        try {
            Object container = walkParents(root);
            if (container == null) throw new IllegalStateException(
                "Cannot write to " + name + " — parent embedded is null");
            rawField.set(container, value);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Field write failed: " + name, e);
        }
    }

    private Object walkParents(Object root) throws IllegalAccessException {
        // Для top-level (parentChain.length == 0) — root и есть container.
        if (parentChain.length == 0) return root;
        // Для embedded — спускаемся по цепочке: для каждого parentFieldId находим
        // в snapshot'е родительский FieldDescriptor и идём по его rawField.
        // Реализация ниже опирается на pre-built parentDescriptorChain (см. ниже).
        Object current = root;
        for (FieldDescriptor parent : parentDescriptorChain) {
            current = parent.rawField.get(current);
            if (current == null) return null;
        }
        return current;
    }

    // Заполняется в финальной фазе bootstrap'а после построения всех FieldDescriptor'ов.
    private FieldDescriptor[] parentDescriptorChain = new FieldDescriptor[0];
    void setParentDescriptorChain(FieldDescriptor[] chain) { this.parentDescriptorChain = chain; }
}
```

### 24.4. `MetadataSnapshotProvider` — DI-обёртка с `Optional` static-fallback

```java
@Component
public class MetadataSnapshotProvider {

    private static volatile MetadataSnapshot STATIC_REF;

    private volatile MetadataSnapshot snapshot;

    public MetadataSnapshot get() {
        var s = snapshot;
        if (s == null) throw new IllegalStateException(
            "MetadataSnapshot not yet built. Bootstrap not complete?");
        return s;
    }

    /**
     * Static accessor для случаев, когда DI недоступна (например, JPA listener,
     * созданный через default-constructor в bootstrap-окне). Возвращает Optional —
     * вызывающий код обязан явно обработать пустой случай.
     */
    public Optional<MetadataSnapshot> staticGet() {
        return Optional.ofNullable(STATIC_REF);
    }

    void publish(MetadataSnapshot s) {
        this.snapshot = s;
        STATIC_REF = s;
        SafeToString.init(this);   // SafeToString снова видит provider после rebuild'а (тесты)
    }
}
```

`staticGet()` возвращает `Optional<MetadataSnapshot>`, потому что в bootstrap-окне (между загрузкой класса и публикацией snapshot'а) static-reference может быть `null`. Все callers (например, `SafeToString.render`) обязаны обработать пустой случай через `.orElse(null)` / `.orElseThrow()`.

### 24.5. `MetadataBootstrapper`

```java
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class MetadataBootstrapper implements SmartInitializingSingleton {

    private final MetadataSnapshotProvider provider;
    private final BootstrapProperties props;
    private final ApplicationContext appCtx;
    private final GrantsProperties grantsProps;
    private final RepositoryRegistry repos;

    @Override
    public void afterSingletonsInstantiated() {
        Map<Long, AggregateDescriptor> aggregates = new HashMap<>();
        Map<Class<?>, Long>            typeIdByClass = new HashMap<>();
        Map<Long, Class<? extends Serializable>> idClassByTypeId = new HashMap<>();
        Map<Long, List<AccessFilterDef>> filters = new HashMap<>();

        try (var sr = new ClassGraph()
                .acceptPackages(props.getScanPackages().toArray(String[]::new))
                .enableAllInfo()
                .scan()) {

            // 1. Сканируем @TypeId (и @Entity, и @MappedSuperclass)
            for (var ci : sr.getClassesWithAnnotation(TypeId.class.getName())) {
                Class<?> cls = ci.loadClass();
                if (!cls.isAnnotationPresent(Entity.class)) continue;     // только агрегаты
                long typeId = cls.getAnnotation(TypeId.class).value();
                if (typeIdByClass.containsKey(cls)
                    || typeIdByClass.containsValue(typeId)) {
                    throw new BootstrapValidationException(
                        "Duplicate @TypeId or class registration: " + cls + ", typeId=" + typeId);
                }
                typeIdByClass.put(cls, typeId);

                // Валидируем: @TypeId на репозитории есть и совпадает
                Class<?> repoIface = repos.repoInterfaceByTypeId(typeId);
                if (repoIface == null) {
                    // допустимо: bootstrap-time сначала собирает все @TypeId-агрегаты, потом
                    // проверит наличие репо в финальной фазе. Здесь сохраняем агрегат.
                }

                AggregateDescriptor desc = buildAggregateDescriptor(cls, typeId);
                aggregates.put(typeId, desc);
                idClassByTypeId.put(typeId, desc.idClass());

                // 2. Собираем @AccessFiltered
                AccessFiltered[] anns = cls.getAnnotationsByType(AccessFiltered.class);
                if (anns.length > 0) {
                    List<AccessFilterDef> defs = new ArrayList<>(anns.length);
                    for (AccessFiltered af : anns) {
                        validateFilterFieldName(af.filterField(), cls);
                        defs.add(buildFilterDef(af, cls, desc));
                    }
                    filters.put(typeId, List.copyOf(defs));
                }
            }
        }

        // 3. Финальная валидация — все fail-fast контракты
        runFailFastValidations(aggregates, typeIdByClass, idClassByTypeId, filters);

        // 4. Транзитивное замыкание для AUTO_TRANSITIVE-фильтров через AccessFilterGraph
        AccessFilterGraph graph = AccessFilterGraph.build(aggregates, filters);
        Map<Long, List<AccessFilterDef>> filtersClosed = closeTransitive(filters, graph);

        // 5. Публикация
        GlobalGrants gg = GlobalGrants.fromConfig(grantsProps);
        var snapshot = new MetadataSnapshot(aggregates, typeIdByClass, idClassByTypeId,
                                             filtersClosed, gg, graph, Instant.now());
        provider.publish(snapshot);

        // 6. Регистрация PostLoadAccessCheckListener для всех @AccessFiltered-типов
        registerImplicitPostLoadCheck(aggregates, filtersClosed);

        // 7. Регистрация event'а в @DomainEventRegistry — это уже сделано в DomainEventRegistry
    }

    private AggregateDescriptor buildAggregateDescriptor(Class<?> cls, long typeId) {
        // 1. Извлекаем @Table.name() или дефолтное имя
        String tableName = extractTableName(cls);
        validateSqlIdentifier(tableName, "table name");

        // 2. id-класс
        Class<? extends Serializable> idClass = extractIdClass(cls);

        // 3. Soft-delete
        boolean softDelete = cls.isAnnotationPresent(org.hibernate.annotations.SoftDelete.class);

        // 4. AccessFiltered?
        boolean accessFiltered = cls.isAnnotationPresent(AccessFiltered.class)
                              || cls.isAnnotationPresent(AccessFiltered.List.class);

        // 5. defaultRepoAccess
        AccessLevel defaultRepoAccess = cls.getAnnotation(TypeId.class)
                .defaultRepoAccess().toLevel();

        // 6. PrefetchDepth
        PrefetchDepth pd = cls.getAnnotation(PrefetchDepth.class);
        int prefetchDepth = (pd != null) ? pd.value() : props.getRefprefetch().getDefaultDepth();

        // 7. Flat field tree
        List<FieldDescriptor> flatFields = collectFieldsRecursive(cls, new long[0]);

        // Уникальность fieldId (глобально среди всех @FieldId этого агрегата)
        Set<Long> seen = new HashSet<>();
        for (FieldDescriptor fd : flatFields) {
            if (!seen.add(fd.fieldId())) {
                throw new BootstrapValidationException(
                    "Duplicate @FieldId(" + fd.fieldId() + ") in " + cls.getSimpleName() +
                    ": collision on " + fd.name());
            }
        }

        // Заполняем parentDescriptorChain для обхода через embedded'ы
        wireParentChains(flatFields);

        Map<Long, FieldDescriptor>   byFid  = new HashMap<>();
        Map<String, FieldDescriptor> byProp = new HashMap<>();
        Map<String, FieldDescriptor> byShort = new HashMap<>();
        for (FieldDescriptor fd : flatFields) {
            byFid.put(fd.fieldId(), fd);
            byProp.put(fd.propertyName(), fd);
            byShort.put(fd.shortName(), fd);
        }
        return new AggregateDescriptor(typeId, cls, tableName, idClass, softDelete,
                                        accessFiltered, defaultRepoAccess, prefetchDepth,
                                        List.copyOf(flatFields), byFid, byProp, byShort);
    }

    /**
     * Рекурсивный сбор полей: проходит @FieldId-аннотированные поля класса, для каждого
     * @Embedded поля рекурсивно спускается внутрь embeddable'а с увеличенным parentChain.
     */
    private List<FieldDescriptor> collectFieldsRecursive(Class<?> cls, long[] parentChain) {
        List<FieldDescriptor> out = new ArrayList<>();
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                FieldId fid = f.getAnnotation(FieldId.class);
                if (fid == null) continue;
                if (Modifier.isStatic(f.getModifiers())) continue;
                AccessLevel defaultAccess = fid.defaultAccess().toLevel();
                long fieldId = fid.value();
                String propertyName = f.getName();
                String shortName = f.getName();
                String fullName = parentChain.length == 0
                    ? shortName
                    : Arrays.stream(parentChain).mapToObj(String::valueOf)
                                                .collect(Collectors.joining(".")) + "." + shortName;
                long parentFieldId = parentChain.length == 0 ? -1L : parentChain[parentChain.length - 1];

                boolean isRef = AggregateReference.class.isAssignableFrom(f.getType());
                long referencedTypeId = -1L;
                if (isRef) {
                    ValidAggregateRef var = f.getAnnotation(ValidAggregateRef.class);
                    if (var == null) throw new BootstrapValidationException(
                        "AggregateReference field " + cls.getName() + "." + f.getName() +
                        " must be annotated with @ValidAggregateRef");
                    referencedTypeId = -2L;   // заполнится post-process'ом, когда все typeId известны
                }

                FieldDescriptor fd = new FieldDescriptor(
                    fieldId, fullName, shortName, propertyName,
                    parentChain, parentFieldId, f.getType(), f, defaultAccess, isRef, referencedTypeId);
                out.add(fd);

                // Рекурсия для @Embedded
                if (f.isAnnotationPresent(Embedded.class) || f.getType().isAnnotationPresent(Embeddable.class)) {
                    long[] childChain = Arrays.copyOf(parentChain, parentChain.length + 1);
                    childChain[parentChain.length] = fieldId;
                    out.addAll(collectFieldsRecursive(f.getType(), childChain));
                }
            }
        }
        return out;
    }

    private void wireParentChains(List<FieldDescriptor> all) {
        Map<Long, FieldDescriptor> byFid = all.stream()
                .collect(Collectors.toMap(FieldDescriptor::fieldId, fd -> fd));
        for (FieldDescriptor fd : all) {
            long[] chain = fd.parentChain();
            FieldDescriptor[] parents = new FieldDescriptor[chain.length];
            for (int i = 0; i < chain.length; i++) parents[i] = byFid.get(chain[i]);
            fd.setParentDescriptorChain(parents);
        }
    }

    private AccessFilterDef buildFilterDef(AccessFiltered ann, Class<?> aggClass, AggregateDescriptor desc) {
        FieldDescriptor fd = desc.fieldByName(ann.filterField());
        if (fd == null) throw new BootstrapValidationException(
            "Filter field '" + ann.filterField() + "' not found in " + aggClass.getName());

        // Имя колонки берём из @Column / @JoinColumn / convention
        String filterColumn = extractColumnName(fd.rawField(), ann.filterField());
        validateSqlIdentifier(filterColumn, "filter column");

        String filterName = ann.filterName().isEmpty()
            ? "filter_" + aggClass.getSimpleName().toLowerCase() + "_" + ann.filterField()
            : ann.filterName();
        return new AccessFilterDef(
            ann.filterField(),
            filterColumn,
            ann.userClaim(),
            ann.referencedTypeId(),
            filterName,
            ann.bypassPolicy(),
            // transitiveBypassTypeIds заполнится в closeTransitive
            new long[0]);
    }

    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");

    private static void validateSqlIdentifier(String name, String kind) {
        if (name == null || !SAFE_IDENTIFIER.matcher(name).matches()) {
            throw new BootstrapValidationException(
                "Unsafe SQL " + kind + ": '" + name + "'. Allowed: [a-zA-Z_][a-zA-Z0-9_]*");
        }
    }

    private static void validateFilterFieldName(String name, Class<?> cls) {
        validateSqlIdentifier(name, "filter field name (on " + cls.getSimpleName() + ")");
    }

    /** Заполняет transitiveBypassTypeIds для фильтров с BypassPolicy.AUTO_TRANSITIVE. */
    private Map<Long, List<AccessFilterDef>> closeTransitive(
            Map<Long, List<AccessFilterDef>> filters, AccessFilterGraph graph) {
        Map<Long, List<AccessFilterDef>> out = new HashMap<>();
        for (var e : filters.entrySet()) {
            List<AccessFilterDef> closed = new ArrayList<>(e.getValue().size());
            for (AccessFilterDef f : e.getValue()) {
                if (f.bypassPolicy() == BypassPolicy.AUTO_TRANSITIVE) {
                    long[] reach = graph.reachableFrom(f.referencedTypeId());
                    closed.add(f.withTransitiveBypass(reach));
                } else {
                    closed.add(f);
                }
            }
            out.put(e.getKey(), List.copyOf(closed));
        }
        return out;
    }

    private void registerImplicitPostLoadCheck(Map<Long, AggregateDescriptor> aggregates,
                                                Map<Long, List<AccessFilterDef>> filters) {
        if (!props.getAccess().isImplicitPostLoadCheck()) return;
        var listener = appCtx.getBean(PostLoadAccessCheckListener.class);
        for (var desc : aggregates.values()) {
            if (filters.getOrDefault(desc.typeId(), List.of()).isEmpty()) continue;
            Class<?> repoIface = repos.repoInterfaceByTypeId(desc.typeId());
            PostLoadAccessCheck explicit = repoIface == null ? null
                    : repoIface.getAnnotation(PostLoadAccessCheck.class);
            boolean enabled = (explicit != null) ? explicit.value()
                                                 : props.getAccess().isImplicitPostLoadCheck();
            if (enabled) listener.registerForTypeId(desc.typeId());
        }
    }

    /** ~19 fail-fast контрактов — см. §24.7. */
    private void runFailFastValidations(
            Map<Long, AggregateDescriptor> aggregates,
            Map<Class<?>, Long> typeIdByClass,
            Map<Long, Class<? extends Serializable>> idClassByTypeId,
            Map<Long, List<AccessFilterDef>> filters) {
        var validator = new BootstrapValidator(aggregates, typeIdByClass, idClassByTypeId,
                                                filters, repos);
        validator.runAll();
    }
}
```

### 24.6. `AccessFilterGraph` — транзитивное замыкание (использует stored `Field`)

```java
public final class AccessFilterGraph {

    private final Map<Long, Set<Long>> reachable;       // typeId → transitively reachable typeIds

    private AccessFilterGraph(Map<Long, Set<Long>> r) { this.reachable = r; }

    /** typeIds, достижимые из startTypeId через AggregateReference-поля (рекурсивно). */
    public long[] reachableFrom(long startTypeId) {
        Set<Long> r = reachable.getOrDefault(startTypeId, Set.of());
        return r.stream().mapToLong(Long::longValue).toArray();
    }

    /** Строит граф через FieldDescriptor.rawField() — без повторных findField'ов. */
    public static AccessFilterGraph build(
            Map<Long, AggregateDescriptor> aggregates,
            Map<Long, List<AccessFilterDef>> filters) {

        // 1. Direct edges: typeId → set of referenced typeIds
        Map<Long, Set<Long>> edges = new HashMap<>();
        for (var desc : aggregates.values()) {
            Set<Long> refs = new HashSet<>();
            for (FieldDescriptor fd : desc.fields()) {
                if (!fd.isAggregateReference()) continue;
                if (fd.referencedTypeId() > 0) {
                    refs.add(fd.referencedTypeId());
                } else {
                    // Резолвим из @ValidAggregateRef через stored Field reference
                    Field rf = fd.rawField();
                    ValidAggregateRef var = rf.getAnnotation(ValidAggregateRef.class);
                    if (var == null) continue;
                    Class<? extends AbstractAggregate<?>> targetCls = var.target();
                    long targetTypeId = aggregates.values().stream()
                            .filter(d -> d.javaClass() == targetCls)
                            .map(AggregateDescriptor::typeId)
                            .findFirst()
                            .orElseThrow(() -> new BootstrapValidationException(
                                "Target type not registered: " + targetCls));
                    refs.add(targetTypeId);
                }
            }
            edges.put(desc.typeId(), refs);
        }

        // 2. Transitive closure (Floyd-Warshall-style)
        Map<Long, Set<Long>> reachable = new HashMap<>();
        for (long startId : edges.keySet()) {
            Set<Long> visited = new HashSet<>();
            Deque<Long> stack = new ArrayDeque<>();
            stack.push(startId);
            while (!stack.isEmpty()) {
                long cur = stack.pop();
                if (!visited.add(cur)) continue;
                for (Long next : edges.getOrDefault(cur, Set.of())) {
                    if (!visited.contains(next)) stack.push(next);
                }
            }
            visited.remove(startId);   // не считаем сам тип
            reachable.put(startId, Set.copyOf(visited));
        }
        return new AccessFilterGraph(Map.copyOf(reachable));
    }
}
```

### 24.7. Список fail-fast контрактов

`BootstrapValidator.runAll()` — запускает следующие проверки. Любая из них падает с `BootstrapValidationException`, и приложение **не стартует**:

| # | Контракт | Сообщение об ошибке |
|---|---|---|
| 1 | `@TypeId.value()` на агрегате уникален | `Duplicate @TypeId(<value>) on <classA> and <classB>` |
| 2 | `@FieldId.value()` глобально уникален в пределах агрегата (включая embedded) | `Duplicate @FieldId(<value>) in <class>: collision on <field1> and <field2>` |
| 3 | Каждое `@Embeddable`-поле имеет `@FieldId` (если хоть одно `persistent`-поле есть) | `Embeddable <class> field <name> missing @FieldId` |
| 4 | `@TypeId` на репозитории совпадает с `@TypeId` на агрегате | `Repo <iface>.@TypeId=<a> != Aggregate <agg>.@TypeId=<b>` |
| 5 | `@AccessFiltered.referencedTypeId()` указывает на существующий зарегистрированный тип | `@AccessFiltered.referencedTypeId=<x> not registered` |
| 6 | `@AccessFiltered.filterField` существует на агрегате как `@FieldId`-поле | `Filter field '<name>' not found in <class>` |
| 7 | `@AccessFiltered.filterField` соответствует regex `[a-zA-Z_][a-zA-Z0-9_]*` | `Unsafe SQL filter field name: '<name>'` |
| 8 | Имя колонки фильтра соответствует тому же regex | `Unsafe SQL filter column: '<name>'` |
| 9 | Имя таблицы агрегата соответствует тому же regex | `Unsafe SQL table name: '<name>'` |
| 10 | `@FilterDef.@ParamDef.type` совпадает с `idClassByTypeId(referencedTypeId)` | `Filter <name>: ParamDef type <a> ≠ referenced id type <b>` |
| 11 | Hibernate-`@Type(...)` UserType на `AggregateReference`-поле совпадает с `@ValidAggregateRef.idType()` | `Field <name>: @Type expects <a>, but @ValidAggregateRef.idType=<b>` |
| 12 | Каждое `AggregateReference`-поле имеет `@ValidAggregateRef` | `AggregateReference field <name> missing @ValidAggregateRef` |
| 13 | `@DomainCallback.typeId()` совпадает с `@TypeId` агрегата в первом параметре | `@DomainCallback typeId=<a> ≠ aggregate typeId=<b>` |
| 14 | Сигнатура `@DomainCallback`-метода — 1 или 2 параметра, второй (если есть) — `AccessContext` | `@DomainCallback signature invalid: <method>` |
| 15 | `@DomainEvent.stableName` уникален среди всех зарегистрированных событий | `Duplicate @DomainEvent.stableName: <name>` |
| 16 | `@AccessChecked(strict=false)` явно требует JavaDoc-обоснование (ArchUnit) | `@AccessChecked(strict=false) requires Javadoc: <class>` |
| 17 | `@PostLoadAccessCheck(false)` явно требует JavaDoc-обоснование (ArchUnit) | `@PostLoadAccessCheck(false) requires Javadoc: <class>` |
| 18 | Все `@Cacheable("aggregate")`-методы возвращают НЕ-`@AccessFiltered` агрегаты | `@Cacheable('aggregate') on filtered type <T> — banned` |
| 19 | Никакого `Class.forName(...)` в `core-eventing` пакете (ArchUnit) | `Class.forName forbidden in core-eventing` |
| 20 | Никакого `SnapshotHelper.deepClone` (ArchUnit; класс удалён) | `SnapshotHelper.deepClone removed — use outboxObjectMapper round-trip` |
| 21 | `app.ddd.access.implicit-post-load-check=false` корректно отключает регистрацию | (warning, не error) |
| 22 | **G10:** `@ElementCollection` поле имеет `@FieldId` | `ElementCollection field <name> missing @FieldId in <class>` |
| 23 | **G10:** Если `@ElementCollection` от `@Embeddable`-типа — у `@Embeddable`-типа есть хотя бы одно `@FieldId`-поле | `ElementCollection element type <ElemClass> has no @FieldId fields — list will be invisible by deny-default` |
| 24 | **G7:** Никаких реализаций `liquibase.change.custom.CustomTaskChange`/`CustomChange` (ArchUnit) | `Liquibase data-seed via CustomTaskChange forbidden — use DataSeedTask` |
| 25 | **G2:** `accessAwareObjectMapper` не используется как Redis-сериализатор для `aggregate`/`dto`/`page`/`mapping-plan`-namespace'ов (ArchUnit + конфиг-тест) | `accessAwareObjectMapper used outside projection-cache — banned` |
| 26 | **G1:** В bootstrap-фазе `EventListenerRegistry` содержит `AccessAwarePreUpdateListener`/`AccessAwarePreInsertListener`/`PostLoadAccessCheckListener` (Spring `@SpringBootTest` integration test) | `Required access listener missing in EventListenerRegistry` |
| 27 | **G5:** `FilteredCountQuery` не использует `EntityManager.createNativeQuery` (ArchUnit) | `FilteredCountQuery must use CriteriaBuilder, not native SQL` |
| 28 | **G10:** Reporting-сервисы используют `AccessAwareReportingService`, не `JdbcTemplate.queryForList`/native (ArchUnit) | `Report service must depend on AccessAwareReportingService` |

Контракты 16–19, 24, 25, 27, 28 — ArchUnit-правила; контракт 26 — `@SpringBootTest` (`AccessListenerRegistrationIT`); 1–15, 20, 22, 23 — runtime-валидаторы в `BootstrapValidator`; 21 — warning при старте.

### 24.8. ArchUnit-правила, не покрытые `BootstrapValidator`

```java
@ArchTest
static final ArchRule no_liquibase_custom_task_for_data_seed =
    noClasses().should().implement(liquibase.change.custom.CustomTaskChange.class)
               .orShould().implement(liquibase.change.custom.CustomChange.class)
               .as("G7: data-seed via CustomTaskChange forbidden — use DataSeedTask")
               .because("Liquibase запускается ДО Spring DI; MetadataSnapshot ещё не построен");

@ArchTest
static final ArchRule access_aware_mapper_only_in_projection_cache =
    fields().that().areAnnotatedWith(Qualifier.class)
            .and().haveAnnotationOfClassWithValue(Qualifier.class, "accessAwareObjectMapper")
            .should().beDeclaredInClassesThat().resideInAnyPackage(
                "..core.web..", "..core.cache.AccessProjectionCache..")
            .as("G2: accessAwareObjectMapper только в projection-cache");

@ArchTest
static final ArchRule filtered_count_must_use_criteria_builder =
    methods().that().areDeclaredIn(FilteredCountQuery.class)
             .should().notCallMethod(EntityManager.class, "createNativeQuery", String.class)
             .as("G5: FilteredCountQuery via CriteriaBuilder, not native SQL");

@ArchTest
static final ArchRule report_services_must_use_AccessAwareReportingService =
    classes().that().areAnnotatedWith(Service.class)
             .and().haveSimpleNameContaining("Report")
             .should().dependOnClassesThat().areAssignableTo(AccessAwareReportingService.class)
             .as("G10: reporting через AccessAwareReportingService");
```

---
## 25. Healthcheck — блокировка трафика до завершения bootstrap'а

Контейнер не должен принимать трафик, пока `MetadataSnapshot` не построен. Иначе первый же запрос в bootstrap-окне получит `IllegalStateException: MetadataSnapshot not yet built`.

```java
@Component
@RequiredArgsConstructor
public class MetadataHealthIndicator implements HealthIndicator {

    private final MetadataSnapshotProvider snapshots;

    @Override
    public Health health() {
        return snapshots.staticGet()
            .map(s -> Health.up()
                .withDetail("typesRegistered", s.allAggregates().size())
                .withDetail("builtAt", s.builtAt())
                .build())
            .orElse(Health.down()
                .withDetail("reason", "MetadataSnapshot not yet built — bootstrap incomplete")
                .build());
    }
}
```

Конфигурация Kubernetes:

```yaml
livenessProbe:
  httpGet:
    path: /actuator/health/liveness
    port: 8080
  initialDelaySeconds: 30
  periodSeconds: 10

readinessProbe:
  httpGet:
    path: /actuator/health/readiness
    port: 8080
  initialDelaySeconds: 10
  periodSeconds: 3
  failureThreshold: 5
```

В `application.yml`:

```yaml
management:
  endpoint:
    health:
      group:
        readiness:
          include: readinessState, db, redis, metadataSnapshot
        liveness:
          include: livenessState
  health:
    metadataSnapshot:
      enabled: true
```

---

## 26. Production checklist

Контрольный список для production-deployment'а. Все пункты должны быть `[x]` перед релизом.

### 26.1. Безопасность доступа

- [ ] **F2.** `app.ddd.access.implicit-post-load-check=true` (default) — все `@AccessFiltered`-агрегаты автоматически защищены от `findById`-leak'а через `@PostLoadAccessCheck`.
- [ ] **F1.** `CaffeineUserAccessProvider.loadFromDb` оборачивает `findById` в `systems.systemReadOnlyForType(userTypeId)` — bootstrap-loop невозможен.
- [ ] **F16.** `SystemAuthentication.getAuthorities()` возвращает реальный набор для каждого `Mode` — `@PreAuthorize('GRANT_ADMIN_ROOT')` работает под `maxPrivileges()`.
- [ ] Все `@AccessChecked(strict=false)` имеют JavaDoc-обоснование.
- [ ] Все `@PostLoadAccessCheck(false)` имеют JavaDoc-обоснование.
- [ ] **F12.** `BootstrapValidator` валидирует имена `filterField`, `filterColumn`, `tableName` по regex `[a-zA-Z_][a-zA-Z0-9_]*`.
- [ ] `GrantService` методы под `@PreAuthorize('GRANT_ADMIN_ROOT')`, audit log заполняется при каждом гранте.
- [ ] `core-audit/AuditFacade` — единственный шлюз к `AuditReader` (ArchUnit-правило).
- [ ] `core-eventing` не содержит `Class.forName(...)` (ArchUnit-правило).

### 26.2. Транзакционность и lifecycle

- [ ] **F4.** `CrossTenantBatchService` использует `TransactionTemplate` с `PlatformTransactionManager`; `@TransactionalEventListener(AFTER_COMMIT)` срабатывает корректно.
- [ ] **G1.** Hibernate listener'ы регистрируются через `hibernate.integrator_provider` (`IntegratorProvider`-bean), не через `statement_inspector`-ключ. **Контракт-тест `AccessListenerRegistrationIT` зелёный** — listener'ы фактически в `EventListenerRegistry`.
- [ ] **F11.** `AggregateLifecycleListener.APP_CTX` инициализируется через `LifecycleProcessorBootstrap.@PostConstruct`.
- [ ] **F10.** `CallbackDispatcher` хранит `(beanName, Method)`, резолвит bean из `ApplicationContext` на каждом invoke.
- [ ] **F18.** `PrincipalRevisionListener` записывает `SYSTEM_PRINCIPAL_ID`-ref для системных операций — ни одна ревизия не имеет null-actor'а.
- [ ] `AuditorAware` отдаёт SYSTEM-ref при отсутствии `AccessContext` — `@CreatedBy`/`@LastModifiedBy` всегда заполнены.
- [ ] `InFlushDatabaseAccessGuard` активен — БД-операции из `@PreFlush` ловятся как ошибка.
- [ ] **F3.** `AccessAwareMappingHelper.applyInbound` использует deep-snapshot через `outboxObjectMapper` round-trip — корректное сравнение коллекций/embedded'ов.
- [ ] **G4.** `PostLoadAccessCheckListener` имеет два режима (`THROW`/`MARK_AND_DROP`); `AccessFilterActivator` устанавливает режим через `PostLoadModeHolder`. Метрика `ddd.postload.dropped{typeId}` экспортируется.

### 26.3. Outbox и messaging

- [ ] **F8.** `DomainEventRegistry` построен; все события имеют `@DomainEvent.stableName`; `KafkaSagaBridge` резолвит классы через registry, не `Class.forName`.
- [ ] **F7.** `IdempotencyGuard.waitForResult` использует Redis Streams (`XADD`/`XREAD BLOCK`) — поздние waiter'ы не теряют результат.
- [ ] Outbox poller обрабатывает каждое сообщение в **отдельной** транзакции (`REQUIRES_NEW`).
- [ ] Outbox использует `SELECT FOR UPDATE SKIP LOCKED`, индекс `outbox_pending_idx` создан.
- [ ] Kafka `partition-key` = `aggregateType:aggregateId` — порядок per-aggregate соблюдается.
- [ ] `OutboxHeaderEnricher` пробрасывает W3C `traceparent`, `tracestate`, baggage.
- [ ] Outbox-consumer идемпотентен через `outbox_processed_messages` + unique constraint.
- [ ] `OutboxCleanupJob` запланирован (cron `0 0 3 * * *`).
- [ ] Axon `EventStorageEngine` отсутствует — saga-state-only (`JpaSagaStore`); внешние события идут через outbox→Kafka.
- [ ] Saga-processor имеет `batch-size=1`, `thread-count=1` для критичных саг.

### 26.4. Кеширование и инвалидация

- [ ] **G2.** `RedisCacheManager` использует `outboxObjectMapper` для namespace'ов `aggregate`/`dto`/`page`/`mapping-plan` и `accessAwareObjectMapper` **только** для `projection`. ArchUnit-правило `access_aware_mapper_only_in_projection_cache` зелёное.
- [ ] **F6.** `AccessProjectionTemplate.crossUserFingerprint` НЕ включает `accessKeyHash` — projection-cache hit-rate высок.
- [ ] **G9.** `AccessProjectionService.fingerprintFor(typeId, instance, ctx)` переключается на per-instance fingerprint при наличии `instanceWriteAcl`-grant'а для типа.
- [ ] **F15.** `AccessKeyHasher.fullHash` включает `AccessMetricPayload.hashCode()` — page-cache инвалидируется при `grantRole`/`grantTypeFlags`.
- [ ] `Hibernate L2 cache` отключён (`hibernate.cache.use_second_level_cache=false`).
- [ ] **OSIV** отключён (`spring.jpa.open-in-view=false`).
- [ ] `RedisCacheManager` использует префикс `v<schema-version>:<cacheName>:` — bump версии при breaking change.
- [ ] `CacheInvalidationListener.@Async` + `@TransactionalEventListener(AFTER_COMMIT)` — отказ Redis'а не блокирует commit.
- [ ] Reverse-индексы (`page-deps`, `page-deps-by-type`, `ref-deps`) обновляются атомарно через `MULTI`/`EXEC`.
- [ ] Никакого `SCAN` на горячем пути.
- [ ] `@Cacheable("aggregate")` валидируется на bootstrap'е — для `@AccessFiltered` запрещён.

### 26.5. Сериализация и API

- [ ] Три `ObjectMapper` bean'а: `outboxObjectMapper` (без access-modifier'а), `accessAwareObjectMapper` (с modifier'ом), `dtoObjectMapper`.
- [ ] **F14.** `AccessAwareEmbeddableWriter` использует **стек path'ов** в `SerializerProvider.attributes` — корректная работа embedded-в-embedded произвольной глубины.
- [ ] **G10.** `AccessAwareEmbeddableWriter.serializeCollectionAsField` push'ит `collection-fieldId` в path-stack — `@ElementCollection` с `@Embeddable`-элементами получает корректную field-level маскировку.
- [ ] **F19.** `AbstractAggregate.toString()` всегда маскирует; `debugDump(ctx)` требует ROOT_READ.
- [ ] **F13.** `@PrefetchDepth` на каждом крупном агрегате; метрика `ddd.refprefetch.truncated{typeId}` мониторится.
- [ ] `RefBatchPrefetcher.findAllById` проходит через AOP-обёрнутый репо — `@AccessFiltered` соблюдается при batch-load'е.
- [ ] `ArchUnit`: прямой `findAllById` запрещён вне `RefBatchPrefetcher`/`AuditFacade`/тестов.

### 26.6. Метаданные и маппинг

- [ ] **F17.** `MetadataSnapshotProvider.staticGet()` возвращает `Optional` — все callers явно обрабатывают пустой случай.
- [ ] **F20.** `FieldDescriptor` хранит `Field` reference; `AccessFilterGraph.build` не делает повторный `findField`.
- [ ] **F9.** `AccessMetric` имеет корректный `equals`/`hashCode` через `AccessMetricPayload` — `Objects.equals(oldVal, newVal)` корректно сравнивает в `PreUpdate` listener'е.
- [ ] **G6.** `AccessLevel` имеет канонический инвариант `flags = expand(flags)` через compact-конструктор record'а.
- [ ] **G3.** `AggregateReferenceXxxUserType`-семейство использует корректные сигнатуры `CompositeUserType` для Hibernate 6 (`SharedSessionContractImplementor`-параметр); mapping-классы реально аннотированы `@Embeddable`. Контракт-тест `AggregateReferenceUuidUserTypeIT` зелёный.
- [ ] **F21.** `AccessAwareAfterMapping` — отдельный `@Component`; mapper'ы подключают через `uses = {AccessAwareAfterMapping.class}`.
- [ ] `MetadataHealthIndicator` зарегистрирован, readiness probe ждёт его.
- [ ] **G7.** `DataSeedHealthIndicator` зарегистрирован; readiness probe ждёт завершения `DataSeedRunner`. ArchUnit запрещает `CustomTaskChange`/`CustomChange`.
- [ ] Все агрегаты имеют `@TypeId`; все `AggregateRepository`-наследники имеют `@TypeId` с тем же значением.
- [ ] Все `AggregateReference`-поля имеют `@ValidAggregateRef` + `@Type(...)` UserType.

### 26.7. Bean Validation

- [ ] `spring.jpa.properties.jakarta.persistence.validation.mode: none` на prod.
- [ ] `Validator.validate(aggregate)` вызывается **в каждом** сервисном методе перед `repo.save` — проверено через интеграционные тесты с `@SpyBean Validator`.
- [ ] `@SpringBootTest` validation-contract-test существует для каждого сервиса, мутирующего агрегаты.

### 26.8. Pageable, count и reporting

- [ ] **G5.** `FilteredCountQuery` использует `CriteriaBuilder` (не native SQL); `additionalSpec` корректно учитывается. ArchUnit `filtered_count_must_use_criteria_builder` зелёный.
- [ ] **G4.** `StableChunkPageRenderer` интегрируется с `PostLoadDropMarker`; `AccessAwarePage.approximateTotal` корректно выставляется.
- [ ] **G10.** `AccessAwareReportingService` используется для всех `SUM`/`COUNT`/`GROUP BY`-запросов на `@AccessFiltered`-типах. ArchUnit `report_services_must_use_AccessAwareReportingService` зелёный.

### 26.9. Соединения и observability

- [ ] HikariCP `leak-detection-threshold=30000` — утечки connection'ов ловятся в логах.
- [ ] Micrometer метрики экспортируются: `ddd.filter.bypassed`, `ddd.postload.access_denied`, `ddd.postload.dropped` (G4), `ddd.outbox.published`, `ddd.outbox.dead`, `ddd.outbox.retry`, `ddd.refprefetch.truncated`.
- [ ] Tracing через OpenTelemetry: span'ы для outbox-publish, saga-handle, projection-cache-hit/miss.
- [ ] Алерты: `ddd.outbox.dead > 0` за 5 мин, `ddd.postload.access_denied{rate} > N`, `ddd.postload.dropped{rate} > N`, `ddd.refprefetch.truncated{rate} > 0`.

### 26.10. Развёртывание

- [ ] **G7.** Liquibase разделён: `change-log` запускается в `contexts: schema` (только DDL); все data-seed'ы реализуют `DataSeedTask` и идут через `DataSeedRunner`. ArchUnit запрещает `CustomTaskChange`/`CustomChange`.
- [ ] `app.cache.schema-version` bump'ится при breaking-изменениях формата сериализации.
- [ ] `HardDeletePurgeJob` запланирован (cron `0 0 4 * * *`).
- [ ] Java 21 virtual threads включены (`spring.threads.virtual.enabled=true`).

---

## 27. Что осознанно НЕ используется

| Технология / паттерн | Причина |
|---|---|
| **`@Filter` для `findById` / `EntityManager.find`** | Hibernate Filter работает только в `select` + `criteria`. `findById`-leak закрывается **подразумеваемой** `@PostLoadAccessCheck` для `@AccessFiltered`-типов (см. F2). |
| **JPA Bean Validation на flush** | `validation.mode=none` глобально. Валидация — явно перед `repo.save`. Контракт enforced через интеграционные тесты, не ArchUnit data-flow. |
| **Hibernate L2 cache** | `@Filter` не учитывается L2; ROOT_READ-bypass для соседних запросов утечёт данные. |
| **`@OpenSessionInView`** | Утечка фильтров в JSON-сериализацию; lazy-loading в response-serialization тред. |
| **Axon `EventStorageEngine`** | Дублирует outbox; внешние observers не должны зависеть от внутреннего bus'а. Axon — только saga-state-store через `JpaSagaStore`. |
| **Axon `EventGateway` вне саг и `core-workflow`** | Внешние события идут только через outbox→Kafka (ArchUnit). |
| **`Class.forName(eventType)` для outbox/saga** | Refactoring-hostile. Замещён `DomainEventRegistry` со стабильными именами через `@DomainEvent.stableName` (см. F8). |
| **Redis Pub/Sub для idempotency wait** | Fire-and-forget: subscriber, появившийся после publish'а, теряет сообщение. Замещён Redis Streams (см. F7). |
| **`Caffeine` L1 для `aggregate`-кеша** | Источник cross-node inconsistency; единственный L1 — `CaffeineUserAccessProvider` (5 мин TTL, инвалидируется явно при `grantRole`). |
| **`SCAN` на инвалидации** | O(N) против Redis; reverse-индексы дают O(1). |
| **JPA `@Cacheable` (Hibernate-level)** | Пересекается со Spring Cache + не учитывает `@Filter`. |
| **`SnapshotHelper.deepClone` для `applyInbound`** | Не клонировал коллекции и embedded'ы корректно — давал ложные «нет изменений». Замещён JSON round-trip через `outboxObjectMapper` (см. F3). |
| **JPA `MERGE` для частичного update'а** | `merge` обновляет ВСЕ поля, ломая `@AccessChecked`. Используем `findByIdLocked` + `mapper.mergeInto` + `repo.save`. |
| **`HibernatePropertiesCustomizer` с ключом `statement_inspector` для регистрации listener'ов** | (v8 ошибка) Этот ключ принимает `StatementInspector`, не `Integrator` — listener'ы не попадают в registry. **v9: `hibernate.integrator_provider` + `IntegratorProvider`-bean (G1).** Контракт-тест `AccessListenerRegistrationIT` обязателен. |
| **ServiceLoader-`Integrator` без Spring DI** | Listener'ы инициализируются до Spring DI → `null`-injection. Замещён `IntegratorProvider`-bean'ом, который через `ObjectProvider` лениво резолвит listener'ов из Spring DI к моменту `integrate()`. |
| **`MethodHandle.bindTo(bean)` для callback'ов** | Связывание с конкретным экземпляром обходит Spring proxy (`@Transactional`/`@Async`/`@Cacheable`). Замещено `(beanName, Method)` + резолв из `ApplicationContext` (см. F10). |
| **`accessKeyHash` в cross-user projection-fingerprint'е** | Раздувает cache-keys (один пользователь = один entry). Замещено fingerprint'ом только из effective-bit'ов (F6). **v9 (G9):** для типов с непустым `instanceWriteAcl` fingerprint включает `instanceId+principalIdRaw` для корректности при per-instance ACL'ях; для остальных — остаётся cross-user shareable. |
| **`@MapperConfig` default-method для `@AfterMapping`** | MapStruct не генерирует вызов default-метода из config-интерфейса. Замещено отдельным `@Component AccessAwareAfterMapping` + `uses=` (см. F21). |
| **`SecurityContextHolder` напрямую вне Spring MVC-thread'а** | Не пропагируется в `@Async`/scheduled/post-commit/Reactor. Замещено `AccessContextHolder` + `Micrometer ContextSnapshot`. |
| **`@PostLoad` на сущности (`@PrePersist`-стиль)** | Конфликт с access-логикой; невозможно бросать `AccessDeniedException` из managed-state. Замещено отдельным `PostLoadAccessCheckListener` с двумя режимами `THROW` / `MARK_AND_DROP` (G4). |
| **JSON-колонка для `AggregateReference`** | Нет FK, нет индексов. Замещено типизированными колонками через `AggregateReferenceXxxUserType`-семейство. |
| **`@ManyToOne` для inter-aggregate links** | Нарушает aggregate boundary (cascade'ы, lazy-loading, single-tx-traversal). Замещено `AggregateReference` + `RefBatchPrefetcher`. |
| **`@PostUpdate`/`@PostPersist` для outbox** | Срабатывают **до** commit'а; недоступны для post-commit-публикации. Используем `TransactionSynchronization.beforeCommit` (для записи в outbox) + outbox-poller для публикации в Kafka. |
| **`accessAwareObjectMapper` как Redis-сериализатор для `aggregate`/`dto`/`page`/`mapping-plan`** | (v8 ошибка) Сохранял частично-замаскированные данные с риском cross-user data leak. **v9 (G2):** `outboxObjectMapper` для raw-namespace'ов; `accessAwareObjectMapper` — только `projection`-namespace, где cache key per-fingerprint детерминирует контекст. |
| **Native SQL в `FilteredCountQuery`** | (v8) Игнорировал `additionalSpec` → incorrect `totalElements`. Также требовал runtime SQL-identifier-валидации в hot-path'е. **v9 (G5):** `CriteriaBuilder` — `additionalSpec` учтён, нет string-concat'а. |
| **`raw-flags` (не-expanded) форма `AccessLevel.flags`** | (v8) Смешение raw/expanded форм давало неконсистентные `equals`/`hashCode`. **v9 (G6):** compact-конструктор `AccessLevel` нормализует `flags = expand(flags)`. |
| **Liquibase `CustomTaskChange` для data-seed'а** | Запускается ДО Spring DI; `MetadataSnapshot` ещё не построен; service-слой роняет `IllegalStateException`. **v9 (G7):** Liquibase = только schema-only DDL; data-seed = `DataSeedTask` через `DataSeedRunner` (после `MetadataSnapshot`). ArchUnit запрещает `CustomTaskChange`/`CustomChange`. |
| **Двойная загрузка `UserAggregate` в `UserAccessContextLoader`** | (v8) Загружали под bootstrap-context'ом, потом снова через `userAccess.metricFor`. **v9 (G8):** `user.getAccess()` напрямую + `warmCache(...)` догревает Caffeine — single round-trip. |
| **`JdbcTemplate.queryForList` для reporting на `@AccessFiltered`-типах** | Bypass'ит Hibernate `@Filter`. **v9 (G10):** `AccessAwareReportingService` через `CriteriaBuilder` с тем же набором access-предикатов, что и в `FilteredCountQuery`. ArchUnit запрещает обходные пути. |

---

## 28. Итоговые принципы (16 пунктов)

1. **Метаданные компилируются один раз на старте.** `MetadataBootstrapper` строит immutable `MetadataSnapshot` в `SmartInitializingSingleton.afterSingletonsInstantiated()`. Hibernate listener'ы регистрируются через `IntegratorProvider`-bean (свойство `hibernate.integrator_provider`, G1) — окно «без access-checks» отсутствует. `MetadataSnapshotProvider.staticGet()` возвращает `Optional` для безопасной работы в bootstrap-окне. Liquibase разделён на schema-only DDL и `DataSeedRunner` после bootstrap'а (G7).

2. **Deny-by-default везде.** Поле без `@FieldId` — невидимо. Репо без `@TypeId` — не регистрируется. Ссылка без `@ValidAggregateRef` — fail-fast. `AccessContext` отсутствует на write — `AccessDeniedException` (для `strict=true` агрегатов). `@PostLoadAccessCheck` подразумевается для всех `@AccessFiltered`-типов и закрывает `findById`-leak. `@ElementCollection` без `@FieldId` — fail-fast (G10).

3. **Три уровня контроля.** Row-level через Hibernate `@Filter` + AOP-pointcut на маркер-аннотации `@AggregateRepository` + автотранзитивный bypass через `AccessFilterGraph`. Field-level через Jackson modifier (со стеком path'ов в `SerializerProvider.attributes` для embedded произвольной глубины, включая `@ElementCollection` per-element — G10), MapStruct hooks (через отдельный `@Component`), Hibernate `PreUpdate`/`PreInsert`. Type-level через `AccessResolver` + `GlobalGrants`. PostLoad имеет два режима: `THROW` для single-row, `MARK_AND_DROP` для коллекций (G4).

4. **Никаких ThreadLocal'ов в маппинге.** `MappingContext` передаётся через MapStruct `@Context` параметр; работает с virtual threads, не требует cleanup'а. `AccessContextHolder` использует `Micrometer ContextSnapshot` для пропагации в `@Async`/Reactor/VT.

5. **`AccessContext` обязателен для всех write-путей.** Системные batch'и/миграции явно используют `SystemAccessContexts.maxPrivileges()` под audit'ом. `SystemAuthentication` имеет реальные authorities — `@PreAuthorize` работает корректно.

6. **Bootstrap-loop разорван узким bypass'ом.** `CaffeineUserAccessProvider.loadFromDb` и `UserAccessContextLoader` оборачивают первый `findById` пользователя в `systems.systemReadOnlyForType(userTypeId)` — ROOT_READ выдаётся **только** для типа `UserAggregate`, а не глобально. `loadFor` берёт `user.getAccess()` напрямую и догревает Caffeine — без двойной загрузки (G8).

7. **Никаких мутаций managed-сущностей в access-обработчиках.** `@PostLoadAccessCheck` только бросает (`THROW`) или помечает в `PostLoadDropMarker` (`MARK_AND_DROP`). `applyInbound` бросает в strict-режиме, иначе откатывает. Field-hiding — только в слоях сериализации.

8. **Один маршрут публикации — outbox.** Доменные события для внешних потребителей идут **только** через outbox→Kafka. Axon — saga-state-store, его `EventBus` не публикует наружу. `KafkaSagaBridge` резолвит события через `DomainEventRegistry.@DomainEvent.stableName`, не `Class.forName`. `IdempotencyGuard.waitForResult` через Redis Streams, не Pub/Sub.

9. **Cache имеет одну точку правды — Redis с раздельными сериализаторами per-namespace (G2).** Никаких L1 для агрегатных кешей; единственный Caffeine — `UserAccessProvider`. `outboxObjectMapper` для raw `aggregate`/`dto`/`page`/`mapping-plan`, `accessAwareObjectMapper` — только для `projection`-namespace. Reverse-индексы (`page-deps-by-type`, `ref-deps`) дают точечную инвалидацию без `SCAN`. Async-default инвалидация: отказ Redis'а не блокирует commit. Projection-fingerprint cross-user (только effective-bits) для типов без instance-ACL; per-(user, instance) — для типов с непустым `instanceWriteAcl` (G9).

10. **Все ID — типизированные колонки.** `AggregateReference` хранится через `AggregateReferenceXxxUserType`-семейство в native UUID/BIGINT/VARCHAR-колонках; mapping-классы — реально аннотированы `@Embeddable`, сигнатуры `CompositeUserType` корректны для Hibernate 6 (G3). FK enforcement работает; индексы используются по плану.

11. **Глобальная уникальность `@FieldId`** — рекурсивная, включая произвольно-глубокие embedded'ы и `@ElementCollection` (G10). `AggregateDescriptor.fields()` — flat tree всех полей с path'ом от корня агрегата. `FieldDescriptor` хранит готовый `Field` reference — никаких повторных `findField` в hot-path'е.

12. **Single-source-of-truth для системного principal'а.** `SYSTEM_PRINCIPAL_ID = "__SYSTEM__"`. `SystemAccessContexts` отдаёт singleton-cached контексты для read-only / read-write / max-privileges / read-only-for-type. `PrincipalRevisionListener` всегда записывает SYSTEM-ref для системных миграций — `null`-actor невозможен.

13. **Bean Validation enforcement через интеграционные тесты, не ArchUnit.** Контракт «`Validator.validate` вызывается перед `repo.save`» проверяется `@SpringBootTest` со `@SpyBean Validator`. ArchUnit-правило `services_mutating_aggregates_must_inject_validator` — слабая дополнительная гарантия. Дополнительный contract-test `AccessListenerRegistrationIT` обязателен (G1).

14. **`AccessMetric` полностью иммутабелен и сравним; `AccessLevel` имеет канонический инвариант.** `AccessMetric.with*`-методы возвращают новый экземпляр. `equals`/`hashCode` через `AccessMetricPayload` (record). `AccessLevel.flags` всегда хранится в expanded-форме через compact-конструктор (G6) — `equals`/`hashCode`/`intersect`/`union` консистентны. `accessKeyHash` вычисляется в два прохода: light-hash (JWT) + full-hash (включая `payload.hashCode()`) — кеши инвалидируются при `grantRole`.

15. **Pageable + count + reporting — единый `CriteriaBuilder`-путь (G5, G10).** `FilteredCountQuery` использует `CriteriaBuilder` (не native SQL), учитывает `additionalSpec`. `AccessAwareReportingService` для агрегаций (`SUM`/`COUNT`/`GROUP BY`) проходит через те же access-предикаты. `AccessAwarePage.approximateTotal` корректно отражает `MARK_AND_DROP`-усечения (G4). Spring proxy сохраняется для callback'ов через `(beanName, Method)`-резолв.

16. **MapStruct hooks — через отдельный `@Component`.** `AccessAwareAfterMapping` — Spring-bean с `@BeforeMapping` (deep-snapshot через `outboxObjectMapper` round-trip) и `@AfterMapping` (outbound-маскирование / inbound-проверка). Mapper'ы подключают через `uses = {AccessAwareAfterMapping.class}` — MapStruct гарантированно генерирует вызов хуков. `AccessAwareMappingHelper.applyInbound` корректно сравнивает коллекции и embedded'ы благодаря deep-snapshot'у.

---
