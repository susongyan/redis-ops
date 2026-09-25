import test from 'node:test'
import assert from 'node:assert/strict'
import {parseConsoleCommand} from './consoleCommand.js'
test('preserves quoting, casing, empty values and extra arguments', () => {
  assert.deepEqual(parseConsoleCommand('set Key "hello world" NX'),{commandName:'SET',arguments:['Key','hello world','NX']})
  assert.deepEqual(parseConsoleCommand("ECHO ''"),{commandName:'ECHO',arguments:['']})
  assert.deepEqual(parseConsoleCommand('PING'),{commandName:'PING',arguments:[]})
  assert.deepEqual(parseConsoleCommand('mod.command key 1 2 3'),{commandName:'MOD.COMMAND',arguments:['key','1','2','3']})
  assert.throws(()=>parseConsoleCommand('SET key "bad'))
})
test('unquoted multi-line paste is rejected instead of joining commands',()=>{
  assert.throws(()=>parseConsoleCommand('GET a\nDEL b'))
  assert.deepEqual(parseConsoleCommand('SET a "line1\nline2"').arguments,['a','line1\nline2'])
})
