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
package org.springframework.batch.experimental.core.repository.persistence.converter;

import org.springframework.batch.experimental.core.repository.persistence.JobInstance;

/**
 * @author Mahmoud Ben Hassine
 */
public class JobInstanceConverter {

	public org.springframework.batch.core.JobInstance toJobInstance(JobInstance source) {
		return new org.springframework.batch.core.JobInstance(source.getJobInstanceId(), source.getJobName());
	}

	public JobInstance fromJobInstance(org.springframework.batch.core.JobInstance source) {
		JobInstance jobInstance = new JobInstance();
		jobInstance.setJobName(source.getJobName());
		jobInstance.setJobInstanceId(source.getInstanceId());
		return jobInstance;
	}

}
