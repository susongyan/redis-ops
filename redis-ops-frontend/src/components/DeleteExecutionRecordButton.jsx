import { useState } from 'react'
import { Modal, message } from 'antd'
import IconActionButton from './IconActionButton.jsx'
import { api } from '../api.js'
import { canDeleteExecutionRecord } from '../executionRecords.js'

export default function DeleteExecutionRecordButton({ type, record, onDeleted }) {
  const [busy, setBusy] = useState(false)
  if (!canDeleteExecutionRecord(type, record)) return null
  return <IconActionButton action="delete" loading={busy} onClick={event => {
    event.stopPropagation()
    Modal.confirm({
      title: `删除记录 ${record.taskNo || `#${record.id}`}？`,
      content: '删除后不再展示该记录及其结果，不修改 Redis 数据。',
      okText: '删除记录', cancelText: '取消', okButtonProps: { danger: true },
      onOk: async () => {
        setBusy(true)
        try {
          await api.deleteExecutionRecord(type, record.id, record.version ?? 0)
          message.success('记录已删除')
        } catch (error) {
          message.error(error.message)
          throw error
        } finally { setBusy(false) }
        onDeleted?.(record.id)
      },
    })
  }}/>
}
