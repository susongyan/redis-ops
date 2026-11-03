import test from 'node:test'
import assert from 'node:assert/strict'
import { THEME_STORAGE_KEY, normalizeTheme, readStoredTheme, persistTheme, applyTheme, themeToken, toggleTheme } from './theme.js'

const memoryStorage = initial => {
  const map = new Map(initial)
  return { getItem: k => (map.has(k) ? map.get(k) : null), setItem: (k, v) => map.set(k, String(v)) }
}

test('normalizeTheme 只认 dark，其余一律归一为 light', () => {
  assert.equal(normalizeTheme('dark'), 'dark')
  assert.equal(normalizeTheme('light'), 'light')
  assert.equal(normalizeTheme('BLUE'), 'light')
  assert.equal(normalizeTheme(undefined), 'light')
  assert.equal(normalizeTheme(null), 'light')
})

test('readStoredTheme 读存储值，缺失/抛错/无 storage 都回退 light', () => {
  assert.equal(readStoredTheme(memoryStorage([[THEME_STORAGE_KEY, 'dark']])), 'dark')
  assert.equal(readStoredTheme(memoryStorage()), 'light')
  assert.equal(readStoredTheme(memoryStorage([[THEME_STORAGE_KEY, 'garbage']])), 'light')
  assert.equal(readStoredTheme({ getItem: () => { throw new Error('denied') } }), 'light')
  assert.equal(readStoredTheme(undefined), 'light')
})

test('persistTheme 写入归一化值，storage 抛错不炸', () => {
  const storage = memoryStorage()
  persistTheme('dark', storage)
  assert.equal(storage.getItem(THEME_STORAGE_KEY), 'dark')
  persistTheme('weird', storage)
  assert.equal(storage.getItem(THEME_STORAGE_KEY), 'light')
  persistTheme('dark', { setItem: () => { throw new Error('quota') } })
})

test('applyTheme 设置 html data-theme，doc 缺失不炸', () => {
  const attributes = {}
  applyTheme('dark', { documentElement: { setAttribute: (k, v) => { attributes[k] = v } } })
  assert.equal(attributes['data-theme'], 'dark')
  applyTheme('nonsense', { documentElement: { setAttribute: (k, v) => { attributes[k] = v } } })
  assert.equal(attributes['data-theme'], 'light')
  applyTheme('dark', undefined)
})

test('themeToken 双主题主色 + 恒定圆角，toggle 往返', () => {
  assert.equal(themeToken('light').colorPrimary, '#1677ff')
  assert.equal(themeToken('dark').colorPrimary, '#1668dc')
  assert.equal(themeToken('dark').borderRadius, 6)
  assert.equal(toggleTheme('light'), 'dark')
  assert.equal(toggleTheme(toggleTheme('dark')), 'dark')
})
