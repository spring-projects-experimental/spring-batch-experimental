# About this repository

This repository contains experimental features in Spring Batch.
Experimental features are *not* intended to be used in production.
They are shared here to be explored by the community and to gather feedback.
Please refer to the [Enabling experimental features](#enabling-experimental-features) section for more details about how to enable experimental features.

The currently available experimental features are the following:

* [MongoDB job repository](#mongodb-job-repository)
* [Composite item reader](#composite-item-reader)

**Important note:** The versioning in this repository follows the [semantic versioning specification](https://semver.org/#spec-item-4).
Public APIs as well as the implementations should not be considered stable and may change at any time :exclamation:

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
    <version>0.2.0</version>
</dependency>
```

Depending on the feature you are testing, other dependencies might be required. This will be mentioned in the section describing the feature.

To build the project and install it in your local Maven repository, use the following command:

```shell
$>./mvnw clean install
```

# MongoDB job repository

*Original issue:* https://github.com/spring-projects/spring-batch/issues/877

This feature introduces new implementations of `JobRepository` and `JobExplorer` for MongoDB.

To test this feature, first import the `spring-batch-experimental` jar as described in the [Enabling experimental features](#enabling-experimental-features) section.

Then, add the following dependencies as well:

```xml
<dependencies>
    <dependency>
        <groupId>org.springframework.data</groupId>
        <artifactId>spring-data-mongodb</artifactId>
        <version>4.1.5</version>
    </dependency>
        <dependency>
        <groupId>org.mongodb</groupId>
        <artifactId>mongodb-driver-sync</artifactId>
        <version>4.9.1</version>
    </dependency>
</dependencies>
```

After that, you need to create the collections and sequences required by the MongoDB `JobRepository` implementation in your MongoDB server instance.
Similar to the DDL scripts provided for relational databases, the MongoShell scripts for MongoDB are provided in [schema-drop-mongodb.js](src/main/resources/org/springframework/batch/experimental/core/schema-drop-mongodb.js) and [schema-mongodb.js](src/main/resources/org/springframework/batch/experimental/core/schema-mongodb.js).

Finally, you can define the MongoDB-based `JobRepository` and use it in your Spring Batch application as a regular `JobRepository`:

```java
@Bean
public JobRepository jobRepository(MongoTemplate mongoTemplate, MongoTransactionManager transactionManager) throws Exception {
    MongoJobRepositoryFactoryBean jobRepositoryFactoryBean = new MongoJobRepositoryFactoryBean();
    jobRepositoryFactoryBean.setMongoOperations(mongoTemplate);
    jobRepositoryFactoryBean.setTransactionManager(transactionManager);
    jobRepositoryFactoryBean.afterPropertiesSet();
    return jobRepositoryFactoryBean.getObject();
}
```

The implementation requires a [MongoTemplate](https://docs.spring.io/spring-data/mongodb/docs/current/reference/html/#mongo-template) to interact with MongoDB and a [MongoTransactionManager](https://docs.spring.io/spring-data/mongodb/docs/current/reference/html/#mongo.transactions.tx-manager) to drive Spring Batch transactions.
Those can be defined as Spring beans in the application context as described in Spring Data MongoDB documentation.

You can find a complete example in the [MongoDBJobRepositoryIntegrationTests](./src/test/java/org/springframework/batch/experimental/core/repository/support/MongoDBJobRepositoryIntegrationTests.java) file.

# Composite item reader

*Original issue:* https://github.com/spring-projects/spring-batch/issues/757

This feature introduces a composite `ItemReader` implementation. Similar to the `CompositeItemProcessor` and `CompositeItemWriter`, the idea is to delegate reading to a list of item readers in order.
This is useful when there is a requirement to read data having the same format from different sources (files, databases, etc). Here is an example:

```java
record Person(int id, String name) {}

@Bean
public FlatFileItemReader<Person> fileItemReader() {
	return new FlatFileItemReaderBuilder<Person>()
			.name("fileItemReader")
			.resource(new ClassPathResource("persons.csv"))
			.delimited()
			.names("id", "name")
			.targetType(Person.class)
			.build();
}

@Bean
public JdbcCursorItemReader<Person> databaseItemReader() {
	String sql = "select * from persons";
	return new JdbcCursorItemReaderBuilder<Person>()
			.name("databaseItemReader")
			.dataSource(dataSource())
			.sql(sql)
			.rowMapper(new DataClassRowMapper<>(Person.class))
			.build();
}

@Bean
public CompositeItemReader<Person> itemReader() {
	return new CompositeItemReader<>(Arrays.asList(fileItemReader(), databaseItemReader()));
}
```

This snippet configures a `CompositeItemReader` with two delegates to read the same data from a flat file and a database table.

You can find a complete example in the [CompositeItemReaderIntegrationTests](./src/test/java/org/springframework/batch/experimental/item/support/CompositeItemReaderIntegrationTests.java) file.

# Contribute

The best way to contribute to this project is by trying out the experimental features and sharing your feedback!

If you find an issue, please report it on the [issue tracker](https://github.com/spring-projects-experimental/spring-batch-experimental/issues). You are welcome to share your feedback on the original issue related to each feature.

# License

[Apache License Version 2.0](./LICENSE.txt).