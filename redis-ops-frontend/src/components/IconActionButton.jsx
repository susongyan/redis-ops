import { Button, Tooltip } from 'antd'
import { DeleteOutlined, EditOutlined } from '@ant-design/icons'

const IconActionButton = forwardRef(function IconActionButton({ action, label, ...props }, ref) {
  const deleting = action === 'delete'
  const title = label || (deleting ? '删除' : '编辑')
  return <Tooltip title={title}><Button ref={ref} size="small" type="text" danger={deleting} aria-label={title} icon={deleting ? <DeleteOutlined /> : <EditOutlined />} {...props} /></Tooltip>
})

export default IconActionButton
import { forwardRef } from 'react'
