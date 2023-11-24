/*
 * Copyright 2023 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.batch.experimental.core.step.item;

import javax.sql.DataSource;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.configuration.annotation.EnableBatchProcessing;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.database.JdbcBatchItemWriter;
import org.springframework.batch.item.database.builder.JdbcBatchItemWriterBuilder;
import org.springframework.batch.item.file.FlatFileItemReader;
import org.springframework.batch.item.file.builder.FlatFileItemReaderBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.test.jdbc.JdbcTestUtils;

/**
 * @author Mahmoud Ben Hassine
 */
public class ChunkOrientedStepIntegrationTests {

	@Test
	void testChunkOrientedStep() throws Exception {
		// given
		System.setProperty("fail", "true");
		ApplicationContext context = new AnnotationConfigApplicationContext(TestConfiguration.class);
		JobLauncher jobLauncher = context.getBean(JobLauncher.class);
		Job job = context.getBean(Job.class);

		// when
		JobParameters jobParameters = new JobParametersBuilder()
				.addString("file", "persons1.csv")
				.toJobParameters();
		JobExecution jobExecution = jobLauncher.run(job, jobParameters);

		// then
		Assertions.assertEquals(ExitStatus.COMPLETED, jobExecution.getExitStatus());
		StepExecution stepExecution = jobExecution.getStepExecutions().iterator().next();
		Assertions.assertEquals(ExitStatus.COMPLETED, stepExecution.getExitStatus());
		Assertions.assertEquals(6, stepExecution.getReadCount());
		Assertions.assertEquals(6, stepExecution.getWriteCount());
		JdbcTemplate jdbcTemplate = new JdbcTemplate(context.getBean(DataSource.class));
		Assertions.assertEquals(6, JdbcTestUtils.countRowsInTable(jdbcTemplate, "person_target"));
	}

	@Test
	void testChunkOrientedStepFailure() throws Exception {
		// given
		System.setProperty("fail", "true");
		ApplicationContext context = new AnnotationConfigApplicationContext(TestConfiguration.class);
		JobLauncher jobLauncher = context.getBean(JobLauncher.class);
		Job job = context.getBean(Job.class);

		// when
		JobParameters jobParameters = new JobParametersBuilder()
				.addString("file", "persons1.csv")
				.toJobParameters();
		JobExecution jobExecution = jobLauncher.run(job, jobParameters);

		// then
		Assertions.assertEquals(ExitStatus.FAILED.getExitCode(), jobExecution.getExitStatus().getExitCode());
		StepExecution stepExecution = jobExecution.getStepExecutions().iterator().next();
		ExitStatus stepExecutionExitStatus = stepExecution.getExitStatus();
		Assertions.assertEquals(ExitStatus.FAILED.getExitCode(), stepExecutionExitStatus.getExitCode());
		Assertions.assertTrue(stepExecutionExitStatus.getExitDescription().contains("Unable to process item Person[id=1, name=foo1]"));
		Assertions.assertEquals(0, stepExecution.getReadCount());
		Assertions.assertEquals(0, stepExecution.getWriteCount());
		Assertions.assertEquals(0, stepExecution.getCommitCount());
		Assertions.assertEquals(1, stepExecution.getRollbackCount());
		JdbcTemplate jdbcTemplate = new JdbcTemplate(context.getBean(DataSource.class));
		Assertions.assertEquals(0, JdbcTestUtils.countRowsInTable(jdbcTemplate, "person_target"));
	}

	@Configuration
	@EnableBatchProcessing
	static class TestConfiguration {

		record Person(int id, String name) {
		}

		@Bean
		@StepScope
		public FlatFileItemReader<Person> itemReader(@Value("#{jobParameters['file']}") Resource file) {
			return new FlatFileItemReaderBuilder<Person>()
					.name("personItemReader")
					.resource(file)
					.delimited()
					.names("id", "name")
					.targetType(Person.class)
					.build();
		}

		@Bean
		public ItemProcessor<Person, Person> itemProcessor() {
			return item -> {
				if (System.getProperty("fail") != null && item.id() == 3) {
					throw new Exception("Unable to process item " + item);
				}
				return new Person(item.id(), item.name().toUpperCase());
			};
		}

		@Bean
		public JdbcBatchItemWriter<Person> itemWriter() {
			String sql = "insert into person_target (id, name) values (:id, :name)";
			return new JdbcBatchItemWriterBuilder<Person>()
					.dataSource(dataSource())
					.sql(sql)
					.beanMapped()
					.build();
		}

		@Bean
		public Step chunkOrientedStep(JobRepository jobRepository, JdbcTransactionManager transactionManager,
									  ItemReader<Person> itemReader, ItemProcessor<Person, Person> itemProcessor, ItemWriter<Person> itemWriter) {
			return new ConcurrentChunkOrientedStep<>("step", 2, itemReader, itemProcessor, itemWriter, jobRepository, transactionManager);
		}


		@Bean
		public Job job(JobRepository jobRepository, Step step) {
			return new JobBuilder("job", jobRepository)
					.start(step)
					.build();
		}

		@Bean
		public DataSource dataSource() {
			return new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
					.addScript("/org/springframework/batch/core/schema-drop-h2.sql")
					.addScript("/org/springframework/batch/core/schema-h2.sql")
					.addScript("schema.sql")
					.generateUniqueName(true)
					.build();
		}

		@Bean
		public JdbcTransactionManager transactionManager(DataSource dataSource) {
			return new JdbcTransactionManager(dataSource);
		}

	}
}
