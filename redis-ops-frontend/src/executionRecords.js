const terminal = {
  RISK_SCAN: ['COMPLETED', 'FAILED', 'CANCELLED'],
  KEY_DISTRIBUTION: ['COMPLETED', 'INCOMPLETE', 'FAILED', 'CANCELLED'],
  VALIDATION: ['PASSED', 'FAILED', 'INCONCLUSIVE', 'CANCELLED'],
  ANALYSIS: ['COMPLETED'],
}
export const canDeleteExecutionRecord = (type, record) => Boolean(record?.id && terminal[type]?.includes(record.status || record.result?.status) && !(type === 'VALIDATION' && record.syncTaskId != null))
