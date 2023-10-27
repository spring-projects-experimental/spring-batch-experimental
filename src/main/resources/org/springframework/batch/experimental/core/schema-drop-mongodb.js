// to execute in MongoShell and update database name `db.` as needed
db.getCollection("BATCH_JOB_INSTANCE").drop();
db.getCollection("BATCH_JOB_EXECUTION").drop();
db.getCollection("BATCH_STEP_EXECUTION").drop();
db.getCollection("BATCH_JOB_INSTANCE_SEQ").drop();
db.getCollection("BATCH_JOB_EXECUTION_SEQ").drop();
db.getCollection("BATCH_STEP_EXECUTION_SEQ").drop();
