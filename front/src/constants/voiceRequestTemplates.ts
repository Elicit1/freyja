/** Editable examples for the form and JSON editor; applied only when the user fills the template. */
export const VOICE_REQUEST_TEMPLATES = {
  DESIGN: JSON.stringify({
    messages: [
      { role: 'user', content: '一位青年男性，声线清亮，吐字清晰，使用自然的普通话。单人干声。' },
      { role: 'assistant', content: '你好，我在这里等你，我们一起出发吧。' },
    ],
  }, null, 2),
  CLONE: JSON.stringify({
    messages: [
      { role: 'user', content: '在“马上”处加重读音，句尾短促。' },
      { role: 'assistant', content: '(愤怒)你为什么还在这里？[提高音量]马上离开！' },
    ],
  }, null, 2),
}
