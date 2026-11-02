# H3 结构化提示词编译模版与实战案例 (H3 Prompt Template & Case Study)

## 1. 通用编译模版 (Standard Six-Segment Template)

### 纯正向表达与诱发词脱敏铁律 (Desensitization & Positive Framing)
1. **正向提示词（Prompt）100% 纯正向**：绝对不在 `prompt` 正文中写“不要...”、“严禁...”、“禁止...”。
2. **彻底脱敏“高危先验诱发词（Cheek/Face）”**：
   - 当提示词中同时出现 `hands`、`left cheek`、`right cheek` 时，扩散模型的跨模态交叉注意力会直接塌缩激活二次元最常见的“双手托腮捧脸（Cute anime face cupping）”先验；
   - **破解法**：将手部目标位置从“脸颊”替换为“向肩部倾斜（leaning toward left/right shoulder at 45 degrees）”，头部主动侧倾依偎。整段提示词完全不出现 `cheek` 词汇，彻底斩断大模型对“双手捧脸”的先验联想！
3. **用“姿态展开”替代“肢体分离”**：不要写“双手解开/分开”，直接以正向身体动作引导（例如“双臂向两侧自然展臂，双腕内旋翻腕绽放兰花指”）。
4. **保持音频主模态声明与原曲绑定**：`<Audio 1>` 必须明确声明为原歌曲及歌声旋律（reference song and vocal track），`overall_soundscape` 必须明确同步于 `<Audio 1>`。
5. **多轮复合动作严禁简写缩略**：多轮循环/交替动作（如第二轮翻腕兰花指）必须完整、独立、详尽地写出每一个手腕的 360 度内旋轨迹、目标空间高度与指形绽放。

```markdown
subject_definitions:
<Picture 1> is the protagonist [Character Name], [Character visual traits, hair, outfit, accessories].
<Picture 2> is the [Scene Environment, lighting, window/background details].
<Audio 1> is the reference song and vocal track for this choreography ('[Lyrics Segment / Song Reference]'). [Character Name] is an exclusively silent dancer and non-vocal subject who keeps lips gently sealed in a peaceful smile.

summary:
[Character Name] completes a pure [dance/action genre] of strictly [N] sequential actions synchronized with <Audio 1>, maintaining an adorable, natural sweet smile throughout: [Action 1], [Action 2], [Action 3], [Action N] gracefully resolving before [Scene Background].

retention_analysis:
Maintain character design from <Picture 1> and environment from <Picture 2>.
[Character Name] is an exclusively silent dancer. Her lips remain gently sealed in a calm, sweet, closed-mouth warm smile throughout the entire duration. Her mouth remains closed and peaceful, focusing purely on the hand dance.
Choreography follows strictly [N] sequential actions:
1. [Action 1 Positive Name and Direction]
2. [Action 2 Positive Name and Direction]
...

detailed_description:
[Camera Type and Movement: e.g. Medium shot with a smooth, continuous camera pull-out (PULL_OUT)].
Throughout the entire shot (00:00.0-00:XX.X), [Character Name] maintains sealed lips in an adorably cute, natural warm smile, performing choreography to <Audio 1> as external music.
00:00.0-00:XX.X (Action 1: [Action 1 Name]):
[Pure Positive Kinematic Geometry Description]
...
```

---

## 2. 真实生产案例实战 (Production Case Studies: 《鲸娘·手势舞》双镜全案)

### 镜头 1 生产案例 (Shot 1, 9.28s - 彻底脱敏捧脸先验与两轮翻腕全量详写版)

```markdown
detailed_description:
Medium shot with elegant, restrained cinematic framing.
Throughout the entire shot (00:00.0-00:09.28), DeepSeek Whale Girl maintains sealed lips in an adorably cute, natural warm smile, performing choreography to <Audio 1> as external music.
00:00.0-00:00.8 (Action 1: Single-Hand Forward Reach):
Camera initiates a gentle micro-push-in (PUSH_IN). Extends right arm directly forward toward camera lens in an open-palm welcoming invitation with relaxed fingers, left arm calmly at side. Closed-mouth smile.
00:00.8-00:01.5 (Action 2: Clasp Hands and Pull Back):
Left hand smoothly rises to meet right hand; fingers clasp together softly into a cupped clasp as both hands draw smoothly back to center chest.
00:01.5-00:02.8 (Action 3: Continuous Two-Finger Forward Striding on Left Forearm):
Left forearm rests horizontally across mid-chest. Right hand inverts downward: entire right hand glides smoothly and continuously forward along left forearm from wrist to elbow, with right index and middle fingers taking prominent alternating forward strides along skin surface, steadily advancing forward toward elbow, concluding crisply at 02.8s.
00:02.8-00:03.5 (Action 4: Hands Over Chest):
Both hands gently flatten over the center of her chest / heart, holding a tender, poised moment.
00:03.5-00:05.5 (Action 5: Single-Blade Clasped Prayer Leans Toward Left Shoulder Then Right Shoulder):
Both hands press flatly together into a single, unified prayer blade:
- 00:03.5-00:04.5 (Left Shoulder Lean): The unified clasped hands tilt at a 45-degree angle toward her LEFT SHOULDER, while her head tilts smoothly to the left to rest gently against the tilted prayer hands with an adorable closed-mouth smile.
- 00:04.5-00:05.5 (Right Shoulder Lean): The unified clasped hands glide smoothly across, tilting at a 45-degree angle toward her RIGHT SHOULDER, while her head tilts symmetrically to the right to rest gently against the tilted prayer hands with a radiant warm smile, resolving cleanly at 05.5s.
00:05.5-00:09.28 (Action 6: Dual Bilateral Wrist-Roll Orchid Fingers FULLY DETAILED FOR BOTH ROUNDS):
Camera glides in a graceful subtle orbital arc (SLIGHT ORBIT ARC).
Across the generous 3.78-second finale, both wrists perform TWO COMPLETE AND EXPLICITLY DETAILED ROUNDS of 360-degree inward circular rolls:
- Round 1 (00:05.5-00:07.2): Both arms extend outward toward chest sides. Both wrists initiate a full 360-degree inward circular roll, spiraling through the carpal joints. As the wrists complete the 360-degree rotation, all ten fingers blossom into classical Chinese dance orchid lotus hands: the right hand blooms upright at head height beside her right temple, while the left hand blooms horizontally in front of her mid-chest, smiling sweetly with sealed lips.
- Round 2 (00:07.2-00:09.28): Continuous motion without pause: both wrists initiate a SECOND INDEPENDENT AND COMPLETE 360-DEGREE INWARD CIRCULAR ROLL, spiraling through the carpal joints once again. Through this second full 360-degree wrist rotation, the hands swap vertical heights: the left arm rises so the left hand blooms upright at head height beside her left temple with classical orchid fingers, while the right arm lowers so the right hand blooms horizontally in front of her mid-chest with classical orchid fingers. DeepSeek Whale Girl maintains this classical left-high right-low bilateral orchid dance pose steadily across the final beats to the end of the shot (09:28), smiling serenely and radiantly into the camera lens as the shot concludes.
```
