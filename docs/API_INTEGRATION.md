# API 配置协议

## AI

当前使用两阶段混合方案，关闭 AI 时两个阶段均不请求网络。通常一次场景计划、一次歌单编排；每个阶段按列表优先级回退，共享 90 秒预算，场景阶段最多 35 秒。

场景计划只发送需求、曲库总数、偏好类型/歌手名和数据充分度，解析为 scene / durationSeconds / count / artists / genres / keywords / exclude / language。本地扫描全部已同步索引后，发送最多 240 首相关候选信息用于编排。不是固定挑选全库最高分的前 240 首。

本地校验所有歌曲 ID、去重，并根据时长补齐或调整。没有时长的歌曲按 4 分钟估计，结果明确说明。API 失败时本地仍可生成，但复杂语义能力有限。不会自动调用嵌入模型、下载本地大模型、覆盖标签或执行 AI 指令。

设置列表从上到下是优先级。只尝试已启用、有密钥的服务。每个最多约 30 秒，总预算约 90 秒；HTTP 错误、超时、JSON 无效或没有有效歌曲都会尝试下一项。多次尝试可能多次计费。

- OpenAI 兼容：地址填到版本根，如 `https://gateway.example.com/v1`；请求 `POST /chat/completions`，Bearer 鉴权，读取 `choices[0].message.content`。也接受完整 `/chat/completions` 地址。DeepSeek 可填 `https://api.deepseek.com`。
- Anthropic：例如 `https://api.anthropic.com`；自动补 `/v1/messages`，`x-api-key` + `anthropic-version: 2023-06-01`，读取 text 内容块。
- Gemini：例如 `https://generativelanguage.googleapis.com/v1beta`；请求 `/models/{model}:generateContent`，`x-goog-api-key`，读取候选文本。

模型 ID 需照服务控制台填写，不假设中转站拥有某个模型。预设均不带密钥，示例域名不能直接作为服务使用。兼容协议并不表示任意服务都已联调。

官方格式参考：[OpenAI](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create)、[Anthropic](https://platform.claude.com/docs/en/api/messages/create)、[Gemini](https://ai.google.dev/api/generate-content)。

## 自定义歌词/图片服务

地址填不带查询参数的 HTTPS 端点。应用发起 GET，并添加 `title`、`artist`、`album`、`kind` 查询参数（kind 为 `lyrics`、`cover` 或 `background`）。配置了自定义 Key 时以 `Authorization: Bearer ...` 传递。仅显式配置的 API 接收该 Key，不给图片 CDN 传 Key。URL 变更时需重填已有 Key。

歌词返回示例：

```json
{"results":[{"title":"曲名","artist":"歌手","album":"专辑","lyrics":"[00:00.00]歌词内容"}]}
```

图片返回示例：

```json
{"results":[{"title":"专辑或图片标题","artist":"歌手","imageUrl":"https://cdn.example.com/art.jpg"}]}
```

也可直接返回数组。图片必须为不需要鉴权、不重定向的公开 HTTPS 链接；拒绝内网地址，最多 8 MB，校验并重新编码为本地 JPEG 后展示。每次最多显示 8 个候选。主进程给预览颁发绑定歌曲与类型的 token，10 分钟后过期。只有确认应用才写入歌曲。

默认歌词来自 [LRCLIB](https://lrclib.net/docs)。默认图片来自 [TheAudioDB](https://www.theaudiodb.com/free_music_api)：封面按艺术家与专辑查询，背景按艺术家查询；留空 Key 使用其公开示例接口，可能有额度/覆盖限制。可填自己的 TheAudioDB Key。

搜索结果不是授权证明；使用前请核对内容来源与使用条款。应用不发布歌词/图片，也不向服务上传音频文件。
