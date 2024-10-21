# About this repository

This repository contains experimental features in Spring Batch.
Experimental features are *not* intended to be used in production.
They are shared here to be explored by the community and to gather feedback.
Please refer to the [Enabling experimental features](#enabling-experimental-features) section for more details about how to enable experimental features.

The currently available experimental features are the following:

* [New chunk-oriented step implementation](#new-chunk-oriented-step-implementation)

**Important note:** The versioning in this repository follows the [semantic versioning specification](https://semver.org/#spec-item-4).
Public APIs should not be considered as stable and may change at any time :exclamation:

# Enabling experimental features

Experimental features are not released to Maven Central, but they are available from the Spring Milestones repository:

```xml
<repositories>
    <repository>
      <id>spring-milestones</id>
      <name>Spring Milestones</name>
      <url>https://repo.spring.io/milestone</url>
      <snapshots>
        <enabled>false</enabled>
      </snapshots>
    </repository>
</repositories>
```

You can also import the latest snapshots from the Spring Snapshots repository:

```xml
<repositories>
    <repository>
        <id>spring-snapshots</id>
        <name>Spring Snapshots</name>
        <url>https://repo.spring.io/snapshot</url>
        <snapshots>
            <enabled>true</enabled>
        </snapshots>
        <releases>
            <enabled>false</enabled>
        </releases>
    </repository>
</repositories>
```

Experimental features are based on the latest Spring Batch 5+ release, which requires Java 17+.

To import experimental features in your project, you need to add the following dependency:

```xml
<dependency>
    <groupId>org.springframework.batch</groupId>
    <artifactId>spring-batch-experimental</artifactId>
    <version>0.4.0</version>
</dependency>
```

Depending on the feature you are testing, other dependencies might be required. This will be mentioned in the section describing the feature.

To build the project and install it in your local Maven repository, use the following command:

```shell
$>./mvnw clean install
```

# New chunk-oriented step implementation

*Original issue:* https://github.com/spring-projects/spring-batch/issues/3950

This is not a new feature, but rather a new implementation of the chunk-oriented processing model. The goal is to address
the problems with the current implementation as explained in [#3950](https://github.com/spring-projects/spring-batch/issues/3950).

The new implementation does **not** address fault-tolerance and concurrency features for the moment. Those will be addressed incrementally
in future versions. Our main focus for now is correctness, ie simplify the code with minimal to no behavioral changes.

The new implementation is in the `ChunkOrientedStep` class, which can be used as follows:

```java
@Bean
public Step chunkOrientedStep(JobRepository jobRepository, JdbcTransactionManager transactionManager,
                              ItemReader<Person> itemReader, ItemProcessor<Person, Person> itemProcessor, ItemWriter<Person> itemWriter) {
    return new ChunkOrientedStep<>("step", 2, itemReader, itemProcessor, itemWriter, jobRepository, transactionManager);
}
```

The first two parameters are the step name and chunk size. Other parameters are self explanatory.
Once defined, this step can then be added to a Spring Batch job flow like any other step type.
You can find a complete example in the [ChunkOrientedStepIntegrationTests](./src/test/java/org/springframework/batch/experimental/core/step/item/ChunkOrientedStepIntegrationTests.java) file.

# Contribute

The best way to contribute to this project is by trying out the experimental features and sharing your feedback!

If you find an issue, please report it on the [issue tracker](https://github.com/spring-projects-experimental/spring-batch-experimental/issues). You are welcome to share your feedback on the original issue related to each feature.

# License

[Apache License Version 2.0](./LICENSE.txt).