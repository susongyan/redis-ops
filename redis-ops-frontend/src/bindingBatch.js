// Not a transaction: preserve successes and retry only failed targets.
export async function addBindings(selected, existing, bind) {
  const failed=[]
  let added=0,skipped=0
  for(const id of new Set(selected)) {
    if(existing.has(id)){skipped++;continue}
    try{await bind(id);added++}catch{failed.push(id)}
  }
  return {added,skipped,failed}
}
