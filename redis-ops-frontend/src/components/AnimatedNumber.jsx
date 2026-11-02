import { useEffect, useRef, useState } from 'react'

// 数值变化时用缓动动画滚到新值，并按方向闪色（升绿降橙）再渐回正常色；值缺失时显示 '-'
export default function AnimatedNumber({ value, duration = 600 }) {
  const [display, setDisplay] = useState(value)
  const [hot, setHot] = useState(null)
  const fromRef = useRef(value)
  const rafRef = useRef(0)
  useEffect(() => {
    if (value == null || fromRef.current === value) return undefined
    if (fromRef.current == null) {
      fromRef.current = value
      setDisplay(value)
      return undefined
    }
    const from = fromRef.current
    const start = performance.now()
    setHot(value > from ? 'up' : 'down')
    const cool = requestAnimationFrame(() => requestAnimationFrame(() => setHot(null)))
    const tick = now => {
      const progress = Math.min(1, (now - start) / duration)
      const eased = 1 - Math.pow(1 - progress, 3)
      const current = Math.round(from + (value - from) * eased)
      fromRef.current = current
      setDisplay(current)
      if (progress < 1) rafRef.current = requestAnimationFrame(tick)
      else {
        fromRef.current = value
        setDisplay(value)
      }
    }
    rafRef.current = requestAnimationFrame(tick)
    return () => {
      cancelAnimationFrame(rafRef.current)
      cancelAnimationFrame(cool)
    }
  }, [value, duration])
  const flash = hot === 'up' ? '#389e0d' : '#fa541c'
  return (
    <span style={hot ? { color: flash, transition: 'none' } : { color: 'inherit', transition: 'color 900ms ease' }}>
      {display == null ? '-' : display.toLocaleString()}
    </span>
  )
}
