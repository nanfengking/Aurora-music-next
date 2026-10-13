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

地址填不带查询参数的 HTTPS 端点。应用发起 GET，并添加 `title`、`artist`、`album`、`kind` 查询参数（Windows 提供 `lyrics`／`cover`，`background` 保留兼容；Android 二级界面提供三种资源）。配置了自定义 Key 时以 `Authorization: Bearer ...` 传递。仅显式配置的 API 接收该 Key。Android 图片仅同源时可带该接口 Key，跨域 CDN 不带；Windows 图片请求不附 Key。URL 变更时需重填已有 Key。

歌词返回示例：

```json
{"results":[{"title":"曲名","artist":"歌手","album":"专辑","lyrics":"[00:00.00]歌词内容"}]}
```

图片返回示例：

```json
{"results":[{"title":"专辑或图片标题","artist":"歌手","imageUrl":"https://cdn.example.com/art.jpg"}]}
```

也可直接返回数组。图片必须使用公开 HTTPS，不得重定向或访问内网；JSON 上限 2 MiB，图片 8 MiB，校验并重新编码为本地 JPEG 后展示。手动查找每次最多显示 8 个候选，确认后才替换现有资源。自动查找默认关闭，需在设置中主动开启；仅对播放中的歌曲补齐缺失资源，不覆盖已有的内嵌或手动资源。

默认歌词来自 [LRCLIB](https://lrclib.net/docs)：自动查找依次尝试歌名＋艺术家＋专辑、歌名＋艺术家、歌名。封面默认优先内嵌、自有和明确配置的来源，不使用公共图片测试入口。Windows 的 TheAudioDB 须显式填写 Key，留空不请求；Android 使用已配置的 JSON 图片服务。两端 iTunes 都默认关闭、可在设置单独确认开启，作为后备来源。Apple 对专辑图用途有限制；开启不代表获得图片授权，请核对 [Apple 接口条款](https://performance-partners.apple.com/search-api)。背景默认在本机由封面放大模糊、无额外请求；Android 也可通过背景 API 手动选择独立背景。

自动查找只发送正在播放歌曲的歌名、艺术家、专辑等必要元数据，不发送音频、WebDAV 凭据或历史记录。失败会静默跳过，不影响播放；未找到结果短时间内不会重复请求。未知艺术家且文件名也无法拆出艺术家时不会自动猜测封面，可手动搜索。

搜索结果不是授权证明；使用前请核对内容来源与使用条款。应用不发布歌词/图片，也不向服务上传音频文件。

Android 资源设置支持最多 10 个服务、独立加密 Bearer Key、排序和启停；每一级歌词信息先按优先级尝试自定义服务，再使用 LRCLIB。接口只允许公共 HTTPS、无查询参数；实际 DNS 解析拒绝私有地址。单次检索预算约 45 秒，取消不计入六小时失败缓存。搜索最多 8 个结果，点击才下载一张预览；凭证绑定歌曲、类型和配置版本，10 分钟有效。关闭或进入后台取消并清理临时预览。图片最长边缩至不超过 768px，音频不重编码。自动封面缓存合计 64 MiB／400 张；手选图片另限 64 MiB／400 张、不自动淘汰，满时须替换或恢复默认。手选封面不被后续音频标签覆盖。
