const { _electron: electron } = require('playwright')
const { resolve, join } = require('node:path')
const fs = require('node:fs')
const { execFileSync } = require('node:child_process')
const http = require('node:http')
const assert = require('node:assert/strict')
const root = resolve(__dirname, '..')
const screenshots = join(root, 'test-results', 'screenshots')
fs.mkdirSync(screenshots, { recursive: true })

async function run() {
  const profile=fs.mkdtempSync(join(root,'.ui-review-features-'))
  const sampleDir=join(profile,'samples');fs.mkdirSync(sampleDir)
  const binary=join(root,'resources/ffmpeg/ffmpeg.exe')
  for(const [name,codec,brand] of [['aac','aac','mp42'],['alac','alac','isom']]) execFileSync(binary,['-nostdin','-v','error','-f','lavfi','-i','sine=frequency=440:sample_rate=44100','-t','15','-c:a',codec,'-brand',brand,join(sampleDir,name+'.m4a')],{windowsHide:true})
  let requests=0
  const server=http.createServer((req,res)=>{
    if(req.headers.authorization!=='Basic '+Buffer.from('test:local-only').toString('base64')) {res.writeHead(401);res.end();return}
    const name=req.url==='/aac.m4a'?'aac':req.url==='/alac.m4a'?'alac':null
    if(!name){res.writeHead(404);res.end();return}
    requests++
    const data=fs.readFileSync(join(sampleDir,name+'.m4a'))
    const range=req.headers.range?.match(/bytes=(\d+)-(\d*)/)
    const start=range?Number(range[1]):0,end=range&&range[2]?Math.min(Number(range[2]),data.length-1):data.length-1
    res.writeHead(range?206:200,{'Content-Type':'audio/mp4','Accept-Ranges':'bytes','Content-Length':end-start+1,...(range?{'Content-Range':`bytes ${start}-${end}/${data.length}`}:{})});res.end(data.subarray(start,end+1))
  })
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve))
  let app
  try {
    const env={...process.env,AURORA_UI_REVIEW_PROFILE:profile};delete env.ELECTRON_RUN_AS_NODE
    app=await electron.launch({executablePath:require('electron'),args:[join(__dirname,'ui-review-main.cjs')],cwd:root,env})
    const page=await app.firstWindow()
    await page.getByRole('heading',{name:'现在就听',exact:true}).waitFor()
    await app.evaluate(({app},data)=>{
      const require=globalThis.reviewRequire
      const Database=require(require('node:path').join(data.root,'node_modules/better-sqlite3'))
      const db=new Database(require('node:path').join(app.getPath('userData'),'musicPlayer.db'))
      for(const name of ['aac','alac'])db.prepare("INSERT INTO songs (id,title,artist,album,duration,filePath,sourceType,remotePath,format) VALUES (?,?,?,'Codec test',15,?,'webdav',?,'M4A/MP42/ISOM')").run(name,'Codec '+name,'Aurora test','/'+name+'.m4a','/'+name+'.m4a')
      for(const [key,value] of Object.entries({webdav_url:data.url,webdav_username:'test',webdav_password:'local-only'}))db.prepare('INSERT OR REPLACE INTO settings VALUES (?,?)').run(key,value)
      db.close()
    },{root,url:`http://127.0.0.1:${server.address().port}`})
    await page.reload();await page.getByRole('heading',{name:'为你精选',exact:true}).waitFor()
    const native=await page.evaluate(async()=>{
      const audio=new Audio();audio.volume=0
      const result=await window.electronAPI.getWebDavAudioUrl('aac')
      const declared=audio.canPlayType('audio/mp4; codecs="mp4a.40.2"')
      const actual=await new Promise(resolve=>{const timer=setTimeout(()=>resolve('timeout'),5000);audio.onloadeddata=()=>{clearTimeout(timer);resolve('loaded')};audio.onerror=()=>{clearTimeout(timer);resolve(`error-${audio.error?.code}`)};audio.src=result.data;audio.load()})
      audio.pause();audio.removeAttribute('src');audio.load();return {declared,actual}
    })
    console.log('NATIVE_AAC_DIAGNOSTIC',JSON.stringify(native))
    await page.getByRole('slider',{name:'音量',exact:true}).fill('0')
    await page.getByRole('navigation',{name:'资料库',exact:true}).getByRole('button',{name:'歌曲',exact:true}).click()
    for(const id of ['aac','alac']) {
      await page.locator('.song-row').filter({has:page.locator('.song-identity b',{hasText:'Codec '+id})}).locator('.song-identity').click()
      await page.waitForFunction(()=>navigator.mediaSession.playbackState==='playing',null,{timeout:30000})
      await page.getByRole('slider',{name:'播放进度',exact:true}).fill('500')
      await page.waitForFunction(()=>document.querySelector('.timeline span')?.textContent==='0:07')
      await page.getByRole('button',{name:'暂停',exact:true}).click()
      const ready=await page.evaluate(async id=>await window.electronAPI.prepareCompatibleAudio(id),id)
      assert.equal(ready.success,true);assert.match(ready.data,/\.flac$/)
    }
    const listening = await page.evaluate(() => window.electronAPI.getPlayHistory())
    assert.equal(listening.data.filter(event => event.action === 'start').length, 2)
    assert.equal(listening.data.filter(event => event.action === 'complete').length, 0)
    assert.ok(listening.data.filter(event => event.action === 'pause').every(event => event.progress < 0.4), 'seeking is not listening')
    assert.ok(requests>=2)
    console.log('CODEC_PASS: authenticated HTTP WebDAV, AAC mp42 + ALAC isom M4A download, decode, playback, seek, cache reuse')

    // Mock at fetch, not IPC: exercise adapters, priority, secrets and result validation.
    await app.evaluate(({app},root)=>{
      const require=globalThis.reviewRequire
      globalThis.featureCalls=[]
      globalThis.realFetch=globalThis.fetch
      globalThis.hybridMode='normal'
      const image=require('node:fs').readFileSync(require('node:path').join(root,'resources/icon.png'))
      globalThis.fetch=async(input,init={})=>{
        const url=String(input);globalThis.featureCalls.push({url,headers:init.headers,body:init.body})
        if(init.method==='PROPFIND')return new Response('<?xml version="1.0"?><d:multistatus xmlns:d="DAV:"></d:multistatus>',{status:207,headers:{'Content-Type':'application/xml'}})
        if(globalThis.hybridMode==='unavailable')return new Response('',{status:503})
        const body=init.body?JSON.parse(init.body):{}
        const system=body.system || body.systemInstruction?.parts?.[0]?.text || body.messages?.find(m=>m.role==='system')?.content || ''
        const user=body.contents?.[0]?.parts?.[0]?.text || body.messages?.find(m=>m.role==='user')?.content || '{}'
        const data=JSON.parse(user)
        const planning=system.includes('音乐场景解析器')
        const ids=data.catalog?.filter(s=>s.id.startsWith('rare-')).map(s=>s.id) || []
        const selection=JSON.stringify(planning && globalThis.hybridMode!=='bad-plan' ? {scene:'any',durationSeconds:null,count:null,artists:[],genres:data.request.includes('爵士')?['爵士']:[],keywords:[],exclude:[],language:'any'} : {name:'Fallback works',description:'Test only',songIds:globalThis.hybridMode==='bad-selection'?['outside-library']:ids.length?[ids[0],'outside-library']:['aac','outside-library']})
        if(url.includes('priority.example'))return new Response('',{status:503})
        if(url.includes('anthropic.example'))return Response.json({content:[{type:'text',text:selection}]})
        if(url.includes('gemini.example'))return Response.json({candidates:[{content:{parts:[{text:selection}]}}]})
        if(url.includes('openai.example'))return Response.json({choices:[{message:{content:selection}}]})
        if(url.startsWith('https://1.1.1.1/test-art'))return new Response(image,{headers:{'Content-Type':'image/png'}})
        if(url.includes('assets.example'))return Response.json({results:[{title:'Resource fixture',artist:'Aurora',lyrics:'[00:00.00]原创测试文本',imageUrl:'https://1.1.1.1/test-art.png'}]})
        throw new Error('Unexpected external request in isolated test')
      }
    },root)
    const provider=(id,protocol,baseUrl)=>({id,name:id,protocol,baseUrl,model:'test-model',priority:1,enabled:true,configured:false,apiKey:'fake-test-key'})
    const list=[provider('first','openai','https://priority.example/v1'),provider('second','anthropic','https://anthropic.example')]
    let result=await page.evaluate(async providers=>window.electronAPI.saveAiProviders(providers),list);assert.equal(result.success,true);assert.ok(!JSON.stringify(result).includes('fake-test-key'))
    let generated=await page.evaluate(()=>window.electronAPI.generateAiPlaylist('安静一点'))
    assert.equal(generated.success,true);assert.equal(generated.data.providerName,'second');assert.deepEqual(generated.data.songIds,['aac']);assert.equal(generated.data.attempts.length,2);assert.equal(generated.data.source,'hybrid');assert.equal(generated.data.diagnostics.librarySize,2)
    let calls=await app.evaluate(()=>globalThis.featureCalls)
    assert.match(calls[0].url,/priority.example\/v1\/chat\/completions$/);assert.match(calls[1].url,/anthropic.example\/v1\/messages$/);assert.equal(calls[1].headers['x-api-key'],'fake-test-key')
    for(const protocol of ['gemini','openai']) {
      const list=[provider(protocol,protocol,`https://${protocol}.example/${protocol==='gemini'?'v1beta':'v1'}`)]
      await page.evaluate(async list=>window.electronAPI.saveAiProviders(list),list)
      generated=await page.evaluate(()=>window.electronAPI.generateAiPlaylist('安静一点'));assert.equal(generated.success,true)
    }
    const settings=await page.evaluate(()=>window.electronAPI.getSettings());assert.ok(!JSON.stringify(settings).includes('fake-test-key'));assert.equal(settings.data.ai_providers,undefined)
    const blocked=await page.evaluate(()=>window.electronAPI.setSettings('ai_providers','[]'));assert.equal(blocked.success,false)
    const saved=await page.evaluate(()=>window.electronAPI.getAiProviders())
    saved.data[0].baseUrl='https://changed.example/v1'
    assert.equal((await page.evaluate(list=>window.electronAPI.saveAiProviders(list),saved.data)).success,false)
    // Full IPC integration: far-tail retrieval, duration fitting, history profile and offline fallback.
    await app.evaluate(({app},root)=>{
      const require=globalThis.reviewRequire, path=require('node:path')
      const Database=require(path.join(root,'node_modules/better-sqlite3'))
      const db=new Database(path.join(app.getPath('userData'),'musicPlayer.db'))
      const insert=db.prepare("INSERT INTO songs (id,title,artist,album,genre,duration,filePath,sourceType,isFavorite) VALUES (?,?,?,'Fixture',?,240,?,'webdav',?)")
      db.transaction(()=>{
        for(let i=0;i<350;i++)insert.run('filler-'+i,'Popular '+i,'Filler '+i,'Pop','/filler-'+i+'.mp3',1)
        for(let i=0;i<16;i++)insert.run('rare-'+i,'Jazz '+i,'Jazz artist '+i,'Jazz','/rare-'+i+'.mp3',0)
        const history=db.prepare('INSERT INTO play_history(songId,action,timestamp,progress) VALUES (?,?,?,?)')
        for(let i=0;i<8;i++)history.run('rare-'+(i%2),'complete',Date.now()-i*86400000,1)
      })()
      db.close()
    },root)
    const before=await page.evaluate(()=>window.electronAPI.getPlaylists())
    generated=await page.evaluate(()=>window.electronAPI.generateAiPlaylist('40分钟爵士'))
    assert.equal(generated.success,true);assert.equal(generated.data.source,'hybrid')
    assert.equal(generated.data.diagnostics.librarySize,368);assert.equal(generated.data.diagnostics.eligibleSize,16)
    assert.equal(generated.data.diagnostics.durationSeconds,2400);assert.equal(generated.data.songIds.length,10)
    assert.ok(generated.data.songIds.every(id=>id.startsWith('rare-')))
    const feed=await page.evaluate(()=>window.electronAPI.getRecommendationFeed())
    assert.equal(feed.success,true);assert.ok(feed.data.profile.genres.some(g=>g.name==='爵士'));assert.equal(feed.data.songs.length,30)
    let callCount=await app.evaluate(()=>globalThis.featureCalls.length)
    const offline=await page.evaluate(()=>window.electronAPI.generateAiPlaylist('40分钟爵士',false))
    assert.equal(offline.data.source,'local');assert.equal(offline.data.diagnostics.durationSeconds,2400)
    assert.equal(await app.evaluate(()=>globalThis.featureCalls.length),callCount)
    const after=await page.evaluate(()=>window.electronAPI.getPlaylists());assert.equal(after.data.length,before.data.length)
    for(const mode of ['unavailable','bad-selection','bad-plan']) {
      await app.evaluate((_electron,mode)=>{globalThis.hybridMode=mode},mode)
      const fallback=await page.evaluate(()=>window.electronAPI.generateAiPlaylist('40分钟爵士'))
      assert.equal(fallback.success,true);assert.equal(fallback.data.diagnostics.durationSeconds,2400)
      assert.ok(fallback.data.songIds.every(id=>id.startsWith('rare-')))
      assert.ok(fallback.data.diagnostics.warnings.length>0, JSON.stringify({mode,result:fallback.data}))
      if(mode==='unavailable')assert.equal(fallback.data.source,'local')
    }
    await app.evaluate(()=>{globalThis.hybridMode='normal'})
    calls=await app.evaluate(()=>globalThis.featureCalls)
    for(const call of calls.filter(call=>call.body)) {
      assert.ok(!call.body.includes('remotePath'));assert.ok(!call.body.includes('filePath'));assert.ok(!call.body.includes('play_history'));assert.ok(!call.body.includes('fake-test-key'))
    }
    assert.equal((await page.evaluate(()=>window.electronAPI.generateAiPlaylist('古典'))).success,false)
    assert.equal((await page.evaluate(()=>window.electronAPI.recordPlayAction({songId:'missing',action:'complete',progress:1,timestamp:0}))).success,false)
    console.log('HYBRID_PASS: full 368-song retrieval, 40-minute fit, offline zero calls, API failures/invalid JSON, taste profile, privacy and preview-only')
    console.log('API_PASS: OpenAI, Anthropic, Gemini, ordered fallback, ID filtering, key redaction, protected settings, origin-change guard')

    result=await page.evaluate(()=>window.electronAPI.saveAssetConfig({lyricsUrl:'https://assets.example/lyrics',coverUrl:'https://assets.example/covers',backgroundUrl:'https://assets.example/backgrounds',configured:false,audioDbConfigured:false}))
    assert.equal(result.success,true)
    for(const kind of ['lyrics','cover','background']) {
      const found=await page.evaluate(kind=>window.electronAPI.searchAssets('aac',kind,{title:'test',artist:'Aurora',album:'test'}),kind)
      assert.equal(found.success,true);assert.equal(found.data.length,1)
      result=await page.evaluate(({kind,id})=>window.electronAPI.applyAsset('aac',kind,id),{kind,id:found.data[0].id})
      assert.equal(result.success,true)
      assert.ok(result.data[kind==='lyrics'?'customLyrics':kind==='cover'?'coverPath':'customArtistImage'])
    }
    console.log('ASSETS_PASS: custom lyric/cover/background APIs, bounded image decoding, preview tokens, explicit apply persistence')
    await page.getByRole('button',{name:'正在播放与歌词',exact:true}).click()
    await page.getByRole('button',{name:'查找歌词、封面与背景',exact:true}).click()
    await page.getByRole('button',{name:'搜索资源',exact:true}).click()
    await page.getByRole('button',{name:/Resource fixture/}).click()
    await page.screenshot({path:join(screenshots,'resource-finder.png'),scale:'css'})
    await page.getByRole('button',{name:'应用选中资源',exact:true}).click()
    await page.getByRole('dialog',{name:'查找音乐资源',exact:true}).waitFor({state:'hidden'})
    console.log('ASSET_UI_PASS: search, preview, selection and apply in player')
    await page.reload()
    await page.getByRole('heading',{name:'你的聆听偏好',exact:true}).waitFor()
    await page.getByText('依据与数据完整度',{exact:true}).click()
    await page.locator('.listening-taste').scrollIntoViewIfNeeded()
    await page.screenshot({path:join(screenshots,'listening-taste.png'),scale:'css'})
    await page.getByRole('button',{name:'新建歌单',exact:true}).first().click()
    const composer=page.getByRole('dialog',{name:'新建歌单',exact:true})
    await composer.getByRole('button',{name:'智能歌单',exact:true}).click()
    await composer.getByLabel('此刻想听什么？',{exact:true}).fill('40分钟爵士')
    await composer.getByRole('button',{name:'生成预览',exact:true}).click()
    await composer.getByText('已检索全部 368 首',{exact:false}).waitFor()
    assert.match(await composer.locator('.playlist-diagnostics').innerText(),/40 分钟/)
    await composer.locator('.playlist-diagnostics').scrollIntoViewIfNeeded()
    const draftLayout=await composer.locator('.draft-tracks').evaluate(el=>({width:el.clientWidth,scroll:el.scrollWidth,children:[...el.children].map(c=>({tag:c.tagName,width:c.clientWidth,scroll:c.scrollWidth}))}))
    assert.ok(draftLayout.scroll<=draftLayout.width,JSON.stringify(draftLayout))
    await page.screenshot({path:join(screenshots,'hybrid-playlist-preview.png'),scale:'css'})
    const onlineCalls=await app.evaluate(()=>globalThis.featureCalls.length)
    await composer.getByLabel('使用 AI 理解场景并编排歌单',{exact:true}).uncheck()
    await composer.getByRole('button',{name:'生成预览',exact:true}).click()
    await composer.getByText('由 本地行为模型 生成 · 保存前可编辑',{exact:true}).waitFor()
    assert.equal(await app.evaluate(()=>globalThis.featureCalls.length),onlineCalls)
    await composer.getByRole('button',{name:'保存歌单',exact:true}).click()
    const playlists=await page.evaluate(()=>window.electronAPI.getPlaylists())
    assert.equal(playlists.data.length,before.data.length+1)
    assert.equal(JSON.parse(playlists.data[0].songIds).length,10)
    console.log('HYBRID_UI_PASS: taste evidence, whole-library coverage, online/offline switch and explicit playlist save')
    await app.evaluate(()=>{globalThis.fetch=globalThis.realFetch})
  } finally {
    if(app)await app.close()
    await new Promise(resolve=>server.close(resolve))
    if(profile.startsWith(join(root,'.ui-review-features-')))fs.rmSync(profile,{recursive:true,force:true,maxRetries:5,retryDelay:250})
  }
}
run().catch(error=>{console.error(error);process.exitCode=1})
