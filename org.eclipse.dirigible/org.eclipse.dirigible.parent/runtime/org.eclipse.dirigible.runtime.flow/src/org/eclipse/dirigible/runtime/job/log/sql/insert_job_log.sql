INSERT INTO DGB_JOB_LOG (
    JOBLOG_INSTANCE,
    JOBLOG_JOB_NAME,
    JOBLOG_JOB_UUID,
    JOBLOG_STATUS,
    JOBLOG_MESSAGE,
    JOBLOG_CONTEXT,
    JOBLOG_TIMESTAMP)
VALUES (?,?,?,?,?,?,$CURRENT_TIMESTAMP$)