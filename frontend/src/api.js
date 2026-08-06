const API_BASE = import.meta.env.VITE_API_BASE || ''

function headers(extra = {}) {
  return {'Content-Type':'application/json','X-Operator':localStorage.getItem('redis-ops-operator')||'local-admin',...extra}
}
function idempotencyKey() { return crypto.randomUUID() }

export async function request(path, options={}) {
  const response=await fetch(`${API_BASE}${path}`,{...options,headers:headers(options.headers)})
  if(response.status===204)return null
  const body=await response.json().catch(()=>({}))
  if(!response.ok)throw new Error(body.message||`请求失败 (${response.status})`)
  return body.data
}

export const api={
  clusters:(params={})=>request(`/api/v1/clusters?${new URLSearchParams(Object.entries(params).filter(([,v])=>v!==undefined&&v!==''))}`),
  cluster:id=>request(`/api/v1/clusters/${id}`),
  createCluster:data=>request('/api/v1/clusters',{method:'POST',headers:{'Idempotency-Key':idempotencyKey()},body:JSON.stringify(data)}),
  updateCluster:(id,version,data)=>request(`/api/v1/clusters/${id}`,{method:'PUT',headers:{'If-Match':String(version),'Idempotency-Key':idempotencyKey()},body:JSON.stringify(data)}),
  testClusterConnection:data=>request('/api/v1/clusters/connection-tests',{method:'POST',body:JSON.stringify(data)}),
  deleteCluster:(id,version)=>request(`/api/v1/clusters/${id}`,{method:'DELETE',headers:{'If-Match':String(version),'Idempotency-Key':idempotencyKey()}}),
  discover:id=>request(`/api/v1/clusters/${id}/discoveries`,{method:'POST',headers:{'Idempotency-Key':idempotencyKey()},body:'{}'}),
  discoveries:id=>request(`/api/v1/clusters/${id}/discoveries`),
  job:id=>request(`/api/v1/jobs/${id}`),
  applications:()=>request('/api/v1/applications'),
  application:id=>request(`/api/v1/applications/${id}`),
  createApplication:data=>request('/api/v1/applications',{method:'POST',headers:{'Idempotency-Key':idempotencyKey()},body:JSON.stringify(data)}),
  updateApplication:(id,version,data)=>request(`/api/v1/applications/${id}`,{method:'PUT',headers:{'If-Match':String(version),'Idempotency-Key':idempotencyKey()},body:JSON.stringify(data)}),
  deleteApplication:(id,version)=>request(`/api/v1/applications/${id}`,{method:'DELETE',headers:{'If-Match':String(version),'Idempotency-Key':idempotencyKey()}}),
  bindApplication:(appId,clusterId,data)=>request(`/api/v1/applications/${appId}/clusters/${clusterId}`,{method:'PUT',headers:{'Idempotency-Key':idempotencyKey()},body:JSON.stringify(data)}),
  unbindApplication:(appId,clusterId)=>request(`/api/v1/applications/${appId}/clusters/${clusterId}`,{method:'DELETE',headers:{'Idempotency-Key':idempotencyKey()}}),
  regions:()=>request('/api/v1/regions')
  ,createRegion:data=>request('/api/v1/regions',{method:'POST',headers:{'Idempotency-Key':idempotencyKey()},body:JSON.stringify(data)})
  ,updateRegion:(id,version,data)=>request(`/api/v1/regions/${id}`,{method:'PUT',headers:{'If-Match':String(version),'Idempotency-Key':idempotencyKey()},body:JSON.stringify(data)})
  ,deleteRegion:(id,version)=>request(`/api/v1/regions/${id}`,{method:'DELETE',headers:{'If-Match':String(version),'Idempotency-Key':idempotencyKey()}})
  ,idcs:(regionId)=>request(`/api/v1/idcs${regionId?`?regionId=${regionId}`:''}`)
  ,createIdc:data=>request('/api/v1/idcs',{method:'POST',headers:{'Idempotency-Key':idempotencyKey()},body:JSON.stringify(data)})
  ,updateIdc:(id,version,data)=>request(`/api/v1/idcs/${id}`,{method:'PUT',headers:{'If-Match':String(version),'Idempotency-Key':idempotencyKey()},body:JSON.stringify(data)})
  ,deleteIdc:(id,version)=>request(`/api/v1/idcs/${id}`,{method:'DELETE',headers:{'If-Match':String(version),'Idempotency-Key':idempotencyKey()}})
  ,audits:(params={})=>request(`/api/v1/audits?${new URLSearchParams(Object.entries(params).filter(([,v])=>v!==undefined&&v!==''))}`)
  ,relations:()=>request('/api/v1/cluster-relations')
  ,relation:id=>request(`/api/v1/cluster-relations/${id}`)
  ,createRelation:data=>request('/api/v1/cluster-relations',{method:'POST',headers:{'Idempotency-Key':idempotencyKey()},body:JSON.stringify(data)})
  ,updateRelation:(id,version,data)=>request(`/api/v1/cluster-relations/${id}`,{method:'PUT',headers:{'If-Match':String(version)},body:JSON.stringify(data)})
  ,deleteRelation:(id,version)=>request(`/api/v1/cluster-relations/${id}`,{method:'DELETE',headers:{'If-Match':String(version)}})
  ,startSwitchover:id=>request(`/api/v1/cluster-relations/${id}/switchovers`,{method:'POST',headers:{'Idempotency-Key':idempotencyKey()},body:'{}'})
  ,syncTasks:(relationId)=>request(`/api/v1/sync-tasks${relationId?`?relationId=${relationId}`:''}`)
  ,syncTask:id=>request(`/api/v1/sync-tasks/${id}`)
  ,syncTaskFullProgress:id=>request(`/api/v1/sync-tasks/${id}/full-progress`)
  ,syncCommandCapabilities:(targetMode,policy={})=>{
    const params=new URLSearchParams({
      targetMode,
      allowDestructiveCommands:String(!!policy.allowDestructiveCommands),
      allowSafeSplit:String(policy.allowSafeSplit!==false),
    })
    ;(policy.additionalBlockedCommands||[]).forEach(command=>params.append('additionalBlockedCommands',command))
    return request(`/api/v1/sync-command-capabilities?${params}`)
  }
  ,syncTaskEvents:(id,page=1,size=20)=>request(`/api/v1/sync-tasks/${id}/events?page=${page}&size=${size}`)
  ,createSyncTask:data=>request('/api/v1/sync-tasks',{method:'POST',headers:{'Idempotency-Key':idempotencyKey()},body:JSON.stringify(data)})
  ,precheckSyncTask:(id,version)=>request(`/api/v1/sync-tasks/${id}/prechecks`,{method:'POST',headers:{'Idempotency-Key':idempotencyKey(),'If-Match':String(version)},body:'{}'})
  ,startSyncTask:(id,version,data)=>request(`/api/v1/sync-tasks/${id}/start`,{method:'POST',headers:{'Idempotency-Key':idempotencyKey(),'If-Match':String(version)},body:JSON.stringify(data)})
  ,pauseSyncTask:(id,version)=>request(`/api/v1/sync-tasks/${id}/pause`,{method:'POST',headers:{'Idempotency-Key':idempotencyKey(),'If-Match':String(version)},body:'{}'})
  ,resumeSyncTask:(id,version)=>request(`/api/v1/sync-tasks/${id}/resume`,{method:'POST',headers:{'Idempotency-Key':idempotencyKey(),'If-Match':String(version)},body:'{}'})
  ,finishSyncTask:(id,version,data)=>request(`/api/v1/sync-tasks/${id}/finish`,{method:'POST',headers:{'Idempotency-Key':idempotencyKey(),'If-Match':String(version)},body:JSON.stringify(data)})
  ,cancelSyncTask:(id,version)=>request(`/api/v1/sync-tasks/${id}/cancel`,{method:'POST',headers:{'Idempotency-Key':idempotencyKey(),'If-Match':String(version)},body:'{}'})
  ,updateSyncLimits:(id,version,data)=>request(`/api/v1/sync-tasks/${id}/limits`,{method:'PUT',headers:{'Idempotency-Key':idempotencyKey(),'If-Match':String(version)},body:JSON.stringify(data)})
  ,confirmSwitchover:(id,version)=>request(`/api/v1/switchovers/${id}/confirm`,{method:'POST',headers:{'Idempotency-Key':idempotencyKey(),'If-Match':String(version)},body:'{}'})
  ,cancelSwitchover:(id,version)=>request(`/api/v1/switchovers/${id}/cancel`,{method:'POST',headers:{'Idempotency-Key':idempotencyKey(),'If-Match':String(version)},body:'{}'})
  ,validationTasks:()=>request('/api/v1/validation-tasks')
  ,validationTask:id=>request(`/api/v1/validation-tasks/${id}`)
  ,createValidationTask:data=>request('/api/v1/validation-tasks',{method:'POST',headers:{'Idempotency-Key':idempotencyKey()},body:JSON.stringify(data)})
  ,startValidationTask:(id,version)=>request(`/api/v1/validation-tasks/${id}/start`,{method:'POST',headers:{'Idempotency-Key':idempotencyKey(),'If-Match':String(version)},body:'{}'})
  ,cancelValidationTask:(id,version)=>request(`/api/v1/validation-tasks/${id}/cancel`,{method:'POST',headers:{'Idempotency-Key':idempotencyKey(),'If-Match':String(version)},body:'{}'})
  ,validationDifferences:(id,page=1,size=20)=>request(`/api/v1/validation-tasks/${id}/differences?page=${page}&size=${size}`)
  ,riskScanTasks:()=>request('/api/v1/risk-scan-tasks')
  ,riskScanTask:id=>request(`/api/v1/risk-scan-tasks/${id}`)
  ,createRiskScanTask:data=>request('/api/v1/risk-scan-tasks',{method:'POST',headers:{'Idempotency-Key':idempotencyKey()},body:JSON.stringify(data)})
  ,startRiskScanTask:(id,version)=>request(`/api/v1/risk-scan-tasks/${id}/start`,{method:'POST',headers:{'Idempotency-Key':idempotencyKey(),'If-Match':String(version)},body:'{}'})
  ,cancelRiskScanTask:(id,version)=>request(`/api/v1/risk-scan-tasks/${id}/cancel`,{method:'POST',headers:{'Idempotency-Key':idempotencyKey(),'If-Match':String(version)},body:'{}'})
  ,riskFindings:(id,page=1,size=20,riskType)=>request(`/api/v1/risk-scan-tasks/${id}/findings?${new URLSearchParams({page,size,...(riskType?{riskType}: {})})}`)
  ,collectorMetrics:async()=>{const response=await fetch('/actuator/prometheus');if(!response.ok)throw new Error(`指标请求失败 (${response.status})`);return response.text()}
  ,collectorNodes:clusterId=>request(`/api/v1/collector/clusters/${clusterId}/nodes`)
  ,alertRules:()=>request('/api/v1/alert-rules')
  ,createAlertRule:data=>request('/api/v1/alert-rules',{method:'POST',headers:{'Idempotency-Key':idempotencyKey()},body:JSON.stringify(data)})
  ,alerts:(status,page=1,size=20)=>request(`/api/v1/alerts?${new URLSearchParams({...(status?{status}:{}),page,size})}`)
  ,acknowledgeAlert:(id,version)=>request(`/api/v1/alerts/${id}/acknowledge`,{method:'POST',headers:{'Idempotency-Key':idempotencyKey(),'If-Match':String(version)},body:'{}'})
  ,resolveAlert:(id,version)=>request(`/api/v1/alerts/${id}/resolve`,{method:'POST',headers:{'Idempotency-Key':idempotencyKey(),'If-Match':String(version)},body:'{}'})
  ,notificationChannels:()=>request('/api/v1/notification-channels')
  ,createNotificationChannel:data=>request('/api/v1/notification-channels',{method:'POST',headers:{'Idempotency-Key':idempotencyKey()},body:JSON.stringify(data)})
  ,updateNotificationChannel:(id,version,data)=>request(`/api/v1/notification-channels/${id}`,{method:'PUT',headers:{'If-Match':String(version),'Idempotency-Key':idempotencyKey()},body:JSON.stringify(data)})
  ,notificationDeliveries:(page=1,size=20)=>request(`/api/v1/notification-deliveries?page=${page}&size=${size}`)
  ,updateAlertRule:(id,version,data)=>request(`/api/v1/alert-rules/${id}`,{method:'PUT',headers:{'If-Match':String(version),'Idempotency-Key':idempotencyKey()},body:JSON.stringify(data)})
  ,ttlGovernanceTasks:()=>request('/api/v1/ttl-governance-tasks')
  ,ttlGovernanceTask:id=>request(`/api/v1/ttl-governance-tasks/${id}`)
  ,createTtlGovernanceTask:data=>request('/api/v1/ttl-governance-tasks',{method:'POST',headers:{'Idempotency-Key':idempotencyKey()},body:JSON.stringify(data)})
  ,dryRunTtlGovernance:(id,version)=>request(`/api/v1/ttl-governance-tasks/${id}/dry-run`,{method:'POST',headers:{'Idempotency-Key':idempotencyKey(),'If-Match':String(version)},body:'{}'})
  ,approveTtlGovernance:(id,version)=>request(`/api/v1/ttl-governance-tasks/${id}/approve`,{method:'POST',headers:{'Idempotency-Key':idempotencyKey(),'If-Match':String(version)},body:'{}'})
  ,startTtlGovernance:(id,version)=>request(`/api/v1/ttl-governance-tasks/${id}/start`,{method:'POST',headers:{'Idempotency-Key':idempotencyKey(),'If-Match':String(version)},body:'{}'})
  ,pauseTtlGovernance:(id,version)=>request(`/api/v1/ttl-governance-tasks/${id}/pause`,{method:'POST',headers:{'Idempotency-Key':idempotencyKey(),'If-Match':String(version)},body:'{}'})
  ,cancelTtlGovernance:(id,version)=>request(`/api/v1/ttl-governance-tasks/${id}/cancel`,{method:'POST',headers:{'Idempotency-Key':idempotencyKey(),'If-Match':String(version)},body:'{}'})
  ,cleanupGovernanceTasks:()=>request('/api/v1/cleanup-governance-tasks')
  ,cleanupGovernanceTask:id=>request(`/api/v1/cleanup-governance-tasks/${id}`)
  ,createCleanupGovernanceTask:data=>request('/api/v1/cleanup-governance-tasks',{method:'POST',headers:{'Idempotency-Key':idempotencyKey()},body:JSON.stringify(data)})
  ,dryRunCleanupGovernance:(id,version)=>request(`/api/v1/cleanup-governance-tasks/${id}/dry-run`,{method:'POST',headers:{'Idempotency-Key':idempotencyKey(),'If-Match':String(version)},body:'{}'})
  ,approveCleanupGovernance:(id,version,note)=>request(`/api/v1/cleanup-governance-tasks/${id}/approve`,{method:'POST',headers:{'Idempotency-Key':idempotencyKey(),'If-Match':String(version)},body:JSON.stringify({note})})
  ,startCleanupGovernance:(id,version)=>request(`/api/v1/cleanup-governance-tasks/${id}/start`,{method:'POST',headers:{'Idempotency-Key':idempotencyKey(),'If-Match':String(version)},body:'{}'})
  ,pauseCleanupGovernance:(id,version)=>request(`/api/v1/cleanup-governance-tasks/${id}/pause`,{method:'POST',headers:{'Idempotency-Key':idempotencyKey(),'If-Match':String(version)},body:'{}'})
  ,cancelCleanupGovernance:(id,version)=>request(`/api/v1/cleanup-governance-tasks/${id}/cancel`,{method:'POST',headers:{'Idempotency-Key':idempotencyKey(),'If-Match':String(version)},body:'{}'})
  ,operationCommands:(writes=false,includeDisabled=false)=>request(`/api/v1/operation-commands?writes=${writes}&includeDisabled=${includeDisabled}`)
  ,updateOperationCommand:(id,version,data)=>request(`/api/v1/operation-commands/${id}`,{method:'PUT',headers:{'Idempotency-Key':idempotencyKey(),'If-Match':String(version)},body:JSON.stringify(data)})
  ,operationPreview:data=>request('/api/v1/redis-operations/preview',{method:'POST',body:JSON.stringify(data)})
  ,createOperation:data=>request('/api/v1/redis-operations',{method:'POST',headers:{'Idempotency-Key':idempotencyKey()},body:JSON.stringify(data)})
  ,operation:id=>request(`/api/v1/redis-operations/${id}`)
  ,operations:(page=1,size=20)=>request(`/api/v1/redis-operations?page=${page}&size=${size}`)
  ,confirmOperation:(id,version)=>request(`/api/v1/redis-operations/${id}/confirm`,{method:'POST',headers:{'Idempotency-Key':idempotencyKey(),'If-Match':String(version)},body:'{}'})
  ,approveOperation:(id,version,note)=>request(`/api/v1/redis-operations/${id}/approve`,{method:'POST',headers:{'Idempotency-Key':idempotencyKey(),'If-Match':String(version)},body:JSON.stringify({note})})
  ,executeOperation:(id,version,data)=>request(`/api/v1/redis-operations/${id}/execute`,{method:'POST',headers:{'Idempotency-Key':idempotencyKey(),'If-Match':String(version)},body:JSON.stringify(data)})
  ,cancelOperation:(id,version)=>request(`/api/v1/redis-operations/${id}/cancel`,{method:'POST',headers:{'Idempotency-Key':idempotencyKey(),'If-Match':String(version)},body:'{}'})
}
