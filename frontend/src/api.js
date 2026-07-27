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
}
