/* ============================================================
   去爬山 GoHiking · 交互原型
   依据 docs/PRD.md v1.7 第 6 / 7 / 8 章实现
   页面编号与 PRD 8.1 完全一致：P-01 ~ P-18
   ============================================================ */
(function () {
'use strict';

/* ---------------- 图标 ---------------- */
var P = {
  map:    'M9 3 3 5.2v15.6L9 19l6 2 6-2V5.2L15 7 9 3z|M9 3v16|M15 7v14',
  route:  'M18 9a2 2 0 1 0 0-4 2 2 0 0 0 0 4z|M6 19a2 2 0 1 0 0-4 2 2 0 0 0 0 4z|M8 19h4a4 4 0 0 0 4-4V9',
  hist:   'M3.5 12a8.5 8.5 0 1 0 2.6-6.1|M3 4v4.5h4.5|M12 7.5V12l3 1.8',
  set:    'M4 7h16|M4 12h16|M4 17h16|M9 5.6a1.9 1.9 0 1 0 0 3.8 1.9 1.9 0 1 0 0-3.8|M15 10.6a1.9 1.9 0 1 0 0 3.8 1.9 1.9 0 1 0 0-3.8|M8 15.6a1.9 1.9 0 1 0 0 3.8 1.9 1.9 0 1 0 0-3.8',
  search: 'M11 18a7 7 0 1 0 0-14 7 7 0 0 0 0 14z|M16.2 16.2 21 21',
  layers: 'M12 3 3 7.5l9 4.5 9-4.5L12 3z|M3 12l9 4.5 9-4.5|M3 16.5 12 21l9-4.5',
  plus:   'M12 5v14|M5 12h14',
  locate: 'M12 21s7-6.3 7-11a7 7 0 1 0-14 0c0 4.7 7 11 7 11z|M12 12.6a2.6 2.6 0 1 0 0-5.2 2.6 2.6 0 0 0 0 5.2z',
  back:   'M15 18l-6-6 6-6',
  chevR:  'M9 18l6-6-6-6',
  close:  'M18 6 6 18|M6 6l12 12',
  flag:   'M5 21V4|M5 4h11l-2 4 2 4H5',
  drop:   'M12 3.5s5.5 6 5.5 9.5a5.5 5.5 0 1 1-11 0C6.5 9.5 12 3.5 12 3.5z',
  up:     'M12 19V5|M6 11l6-6 6 6',
  down:   'M12 5v14|M6 13l6 6 6-6',
  cam:    'M3 8.5A2 2 0 0 1 5 6.5h2l1.2-2h7.6L17 6.5h2a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-9z|M12 16.5a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7z',
  play:   'M7 4.5v15l13-7.5-13-7.5z',
  pause:  'M8.5 5v14|M15.5 5v14',
  stop:   'M6.5 6.5h11v11h-11z',
  check:  'M4.5 12.5 9 17l10.5-10.5',
  share:  'M4 12v7a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-7|M16 6l-4-4-4 4|M12 2v13',
  exp:    'M12 3v12|M7 11l5 5 5-5|M4 20h16',
  imp:    'M12 17V5|M7 9l5-5 5 5|M4 20h16',
  globe:  'M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18z|M3.5 9h17|M3.5 15h17|M12 3c2.5 2.6 3.8 5.5 3.8 9S14.5 18.4 12 21c-2.5-2.6-3.8-5.5-3.8-9S9.5 5.6 12 3z',
  trash:  'M4 7h16|M9 7V4.5h6V7|M6.5 7l1 13h9l1-13|M10 11v6|M14 11v6',
  dots:   'M12 6.6a1.5 1.5 0 1 0 0-3 1.5 1.5 0 0 0 0 3z|M12 13.5a1.5 1.5 0 1 0 0-3 1.5 1.5 0 0 0 0 3z|M12 20.4a1.5 1.5 0 1 0 0-3 1.5 1.5 0 0 0 0 3z',
  edit:   'M4 20h4L19 9l-4-4L4 16v4z|M14 6l4 4',
  warn:   'M12 3 2.5 20h19L12 3z|M12 9.5v5|M12 18h.01',
  folder: 'M3 7a2 2 0 0 1 2-2h4l2 2.5h8a2 2 0 0 1 2 2V18a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7z',
  file:   'M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8l-5-5z|M14 3v5h5',
  mtn:    'M3 19h18|M8.5 10 12 5l3.5 5|M6 14l3-4 3 4 2-2.5 2 2.5',
  refresh:'M20 12a8 8 0 1 1-2.4-5.7|M20 4v5h-5',
  clock:  'M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18z|M12 7.5V12l3 2',
  steps:  'M4 20h16|M7 20v-6|M12 20v-9|M17 20v-4',
  info:   'M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18z|M12 11v5|M12 8h.01',
  filter: 'M3 5h18|M6 12h12|M10 19h4',
  img:    'M3 5.5A2 2 0 0 1 5 3.5h14a2 2 0 0 1 2 2v13a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-13z|M8.5 10a1.8 1.8 0 1 0 0-3.6 1.8 1.8 0 0 0 0 3.6z|M21 16l-5-5-9 9'
};
function ic(n, cls) {
  var d = P[n] || '';
  var paths = d.split('|').map(function (p) { return '<path d="' + p + '"/>'; }).join('');
  return '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.85" ' +
         'stroke-linecap="round" stroke-linejoin="round"' + (cls ? ' class="' + cls + '"' : '') + '>' + paths + '</svg>';
}

/* ---------------- 国际化 ---------------- */
var STR = {
  zh: {
    app:'去爬山', tab_map:'地图', tab_plan:'计划', tab_hist:'记录', tab_me:'我的',
    cancel:'取消', confirm:'确定', save:'保存', del:'删除', edit:'编辑', rename:'重命名',
    share:'分享', exp:'导出', imp:'导入', back:'返回', done:'完成', close:'关闭',
    search:'搜索', next:'下一步', allow:'允许', allowed:'已允许', deny:'暂不',
    km:'公里', mi:'英里', m:'米', ft:'英尺', step:'步', kcal:'千卡',

    p01_t:'需要以下权限', p01_s:'去爬山是本地优先的应用，所有数据只存在你的手机里。以下权限逐项说明用途，可稍后在设置中修改。',
    p01_loc:'精确位置（GPS）', p01_loc_d:'用于记录完整轨迹、海拔与距离。拒绝则核心功能不可用。',
    p01_notif:'通知', p01_notif_d:'用于记录状态常驻通知栏，锁屏时也能看到进度。',
    p01_sensor:'身体活动（计步）', p01_sensor_d:'用于统计步数。拒绝则降级为加速度计算法估算。',
    p01_media:'照片与视频', p01_media_d:'用于按拍摄位置把照片聚合到地图上。拒绝则照片地图不可用。',
    p01_all:'全部允许', p01_skip:'稍后设置', p01_need:'需要先授予定位权限',

    p02_hint:'点击地图选点 · 搜索地名 · 点选地图标记',
    p02_plan:'计划线路', p02_rec:'开始记录',

    p03_t:'选择起终点', p03_start:'起点', p03_end:'终点', p03_pick:'在地图上点击选择',
    p03_cur:'用我的位置', p03_swap:'交换', p03_next:'下一步：查看推荐',
    p03_need:'请先选择起点和终点', p03_manual:'搜不到？手动规划',
    p03_ph:'搜索山名、登山口、停车场',
    p03_tip_poi:'兴趣点', p03_tip_bus:'公交线路（不支持）', p03_tip_kw:'关键词',

    p04_t:'推荐线路', p04_a:'方案 A · 最优', p04_b:'方案 B · 最短', p04_c:'方案 C · 缓坡',
    p04_est:'预计', p04_asc:'爬升', p04_dist:'距离', p04_diff:'难度', p04_est_tag:'(估算)',
    p04_re:'重新规划', p04_manual:'手动打点', p04_choose:'选择此线路',
    p04_src:'高德步行路径规划 + 自建海拔爬升评估（高德无「登山」模式）',

    p05_t:'手动打途经点', p05_hint:'依次点击地图添加途经点，相邻两点自动连线。适用于野山、无路网区域。',
    p05_n:'已添加', p05_undo:'撤销上一个', p05_straight:'直线连接', p05_snap:'路径吸附',
    p05_finish:'生成线路', p05_need:'至少添加 2 个途经点', p05_limit:'途经点最多 50 个',

    p06_t:'保存计划线路', p06_name:'线路名称', p06_note:'备注（可选）', p06_sum:'全程汇总',

    p07_t:'计划线路', p07_new:'新建计划', p07_empty_t:'还没有计划线路',
    p07_empty_d:'出发前在地图上规划好「怎么上去、怎么下来」，爬山时可以看到蓝线做参照。',
    p07_view:'在地图上查看', p07_assoc:'记录时关联',

    p08_alert:'提醒', p08_on:'开', p08_off:'关', p08_dist:'距离', p08_time:'时长',
    p08_alt:'当前海拔', p08_asc:'累计爬升', p08_steps:'步数',
    p08_mark:'打点', p08_pause:'暂停', p08_resume:'继续', p08_summit:'登顶', p08_stop:'停止',
    p08_gps:'GPS 估算', p08_paused:'已暂停',

    p09_t:'结束记录', p09_q:'要保存这次记录吗？', p09_name:'记录名称', p09_note:'备注（可选）',
    p09_discard:'丢弃', p09_discard_q:'确定丢弃这次记录吗？丢弃后无法恢复。',

    p10_t:'运动记录', p10_tt:'总计', p10_times:'次', p10_empty_t:'还没有运动记录',
    p10_empty_d:'第一次爬山记录从这里开始。点击「开始记录」，轨迹、海拔、步数会自动记下来。',

    p11_ov:'概览', p11_ch:'图表', p11_sp:'分段', p11_mk:'标记', p11_ph:'照片',
    p11_out:'去程', p11_ret:'返程', p11_altchart:'海拔曲线',
    p11_moving:'运动时长', p11_pause:'暂停时长', p11_desc:'累计下降',
    p11_max:'最高海拔', p11_min:'最低海拔', p11_avgspd:'平均速度', p11_pace:'平均配速',
    p11_cal:'卡路里', p11_splits:'分段（每公里）', p11_nomk:'暂无标记',
    p11_src_alt:'海拔来源', p11_src_step:'步数来源',

    p12_t:'照片地图', p12_sheet:'张照片', p12_nogps:'无位置信息',
    p12_empty_t:'没有带位置信息的照片', p12_empty_d:'授予照片权限后，手机里带 GPS 的照片会按拍摄位置聚合到这里。',

    p13_t:'查看', p13_map:'在地图上查看',

    p14_t:'设置', p14_about:'关于', p14_ver:'版本',
    g_general:'通用', g_alert:'提醒', g_rec:'记录', g_unit:'单位', g_map:'地图', g_data:'数据', g_about:'关于',
    s_lang:'语言', s_lang_v:['跟随系统','简体中文','English'],
    s_alert:'提醒总开关', s_voice:'语音播报', s_dalert:'距离提醒', s_dval:'距离阈值',
    s_aalert:'海拔提醒', s_aval:'海拔阈值', s_vibe:'打点震动反馈',
    s_locmode:'定位模式', s_locmode_v:['高精度','省电'], s_autopause:'自动暂停',
    s_screenon:'屏幕常亮', s_bg:'后台记录',
    s_udist:'距离单位', s_udist_v:['公里','英里'], s_ualt:'海拔单位', s_ualt_v:['米','英尺'],
    s_pace:'配速显示', s_pace_v:['配速','速度'],
    s_maptype:'地图类型', s_maptype_v:['标准','卫星'], s_lw:'轨迹线宽', s_lw_v:['细','中','粗'],
    s_photocl:'显示照片聚合',
    s_crs:'导出坐标系', s_crs_v:['GCJ-02','WGS-84'], s_gpx:'附加导出格式', s_gpx_v:['GPX','无'],
    s_media:'备份包含媒体文件', s_cksum:'备份包含校验和', s_conflict:'导入冲突默认策略',
    s_conflict_v:['跳过','覆盖','另存副本','逐条询问'],
    s_expsingle:'导出单条记录 / 批量导出', s_expzip:'导出全量备份（ZIP）', s_impbtn:'导入数据（JSON / ZIP）',
    s_alert_note:'「海拔阈值」是唯一的海拔相关阈值——它同时决定海拔提醒的播报频率和海拔打点的密度。',
    s_nobar:'无气压计设备：该阈值下限自动提到 30 米。',

    p15_t:'关于', p15_repo:'开源地址', p15_lic:'开源协议', p15_priv:'隐私政策', p15_issue:'问题反馈',
    p15_desc:'以地图为核心，面向登山/徒步爱好者的「计划 — 记录 — 回顾」一体化应用。数据只存在本地，可随时完整导出。',

    p16_t:'导出', p16_prep:'正在准备…', p16_done:'导出完成', p16_open:'打开所在文件夹',
    p16_cancel_q:'确定取消导出吗？', p16_file:'文件名',

    p17_t:'导入预览', p17_file:'文件', p17_will:'将导入', p17_conflict:'冲突',
    p17_crs_warn:'该文件的坐标系为 WGS-84，导入时会自动转换为内部使用的 GCJ-02。',
    p17_policy:'冲突处理策略', p17_start:'开始导入', p17_onlyview:'仅预览不导入',
    p17_cnt_t:'运动记录', p17_cnt_r:'计划线路', p17_cnt_m:'媒体文件', p17_range:'时间范围',

    p18_t:'导入结果', p18_ok:'成功', p18_skip:'跳过', p18_fail:'失败', p18_reason:'失败原因',
    p18_pol:'跳过（默认）', p18_pol2:'覆盖', p18_pol3:'另存副本', p18_pol4:'逐条询问'
  },
  en: {
    app:'GoHiking', tab_map:'Map', tab_plan:'Plan', tab_hist:'History', tab_me:'Me',
    cancel:'Cancel', confirm:'OK', save:'Save', del:'Delete', edit:'Edit', rename:'Rename',
    share:'Share', exp:'Export', imp:'Import', back:'Back', done:'Done', close:'Close',
    search:'Search', next:'Next', allow:'Allow', allowed:'Allowed', deny:'Not now',
    km:'km', mi:'mi', m:'m', ft:'ft', step:'steps', kcal:'kcal',

    p01_t:'Permissions needed', p01_s:'GoHiking is local-first — all data stays on your device. Each permission is explained below and can be changed later in Settings.',
    p01_loc:'Precise location (GPS)', p01_loc_d:'For recording the full track, altitude and distance. Denying this disables core features.',
    p01_notif:'Notifications', p01_notif_d:'For the persistent recording notification you can see while the screen is off.',
    p01_sensor:'Activity recognition (steps)', p01_sensor_d:'For step counting. If denied, falls back to an accelerometer algorithm.',
    p01_media:'Photos and videos', p01_media_d:'To cluster photos on the map by where they were taken. Denying this disables Photo Map.',
    p01_all:'Allow all', p01_skip:'Set later', p01_need:'Location permission is required',

    p02_hint:'Tap the map · Search a place · Tap a map label',
    p02_plan:'Plan route', p02_rec:'Start recording',

    p03_t:'Pick start / end', p03_start:'Start', p03_end:'End', p03_pick:'Tap the map to choose',
    p03_cur:'Use my location', p03_swap:'Swap', p03_next:'Next: view suggestions',
    p03_need:'Please pick both start and end', p03_manual:'No results? Plan manually',
    p03_ph:'Search mountain, trailhead, parking',
    p03_tip_poi:'Place', p03_tip_bus:'Bus line (unsupported)', p03_tip_kw:'Keyword',

    p04_t:'Suggested routes', p04_a:'Option A · Best', p04_b:'Option B · Shortest', p04_c:'Option C · Gentle',
    p04_est:'Est.', p04_asc:'Ascent', p04_dist:'Distance', p04_diff:'Difficulty', p04_est_tag:'(est.)',
    p04_re:'Re-plan', p04_manual:'Add points manually', p04_choose:'Use this route',
    p04_src:'AMap walking routing + our own ascent model (AMap has no hiking mode)',

    p05_t:'Add waypoints manually', p05_hint:'Tap the map to add waypoints in order; consecutive points are connected automatically. For untracked mountains and areas without road data.',
    p05_n:'Added', p05_undo:'Undo last', p05_straight:'Straight line', p05_snap:'Snap to paths',
    p05_finish:'Build route', p05_need:'Add at least 2 waypoints', p05_limit:'Maximum 50 waypoints',

    p06_t:'Save planned route', p06_name:'Route name', p06_note:'Note (optional)', p06_sum:'Full trip',

    p07_t:'Planned routes', p07_new:'New plan', p07_empty_t:'No planned routes yet',
    p07_empty_d:'Plan how to go up and how to come down before you leave; the blue line shows as a reference while hiking.',
    p07_view:'Show on map', p07_assoc:'Link while recording',

    p08_alert:'Alerts', p08_on:'On', p08_off:'Off', p08_dist:'Distance', p08_time:'Duration',
    p08_alt:'Altitude', p08_asc:'Ascent', p08_steps:'Steps',
    p08_mark:'Mark', p08_pause:'Pause', p08_resume:'Resume', p08_summit:'Summit', p08_stop:'Stop',
    p08_gps:'GPS est.', p08_paused:'Paused',

    p09_t:'Finish recording', p09_q:'Save this trip?', p09_name:'Trip name', p09_note:'Note (optional)',
    p09_discard:'Discard', p09_discard_q:'Discard this trip? This cannot be undone.',

    p10_t:'Trips', p10_tt:'Total', p10_times:'trips', p10_empty_t:'No trips yet',
    p10_empty_d:'Your first hike starts here. Tap Start recording and the track, altitude and steps are logged automatically.',

    p11_ov:'Overview', p11_ch:'Charts', p11_sp:'Splits', p11_mk:'Markers', p11_ph:'Photos',
    p11_out:'Outbound', p11_ret:'Return', p11_altchart:'Altitude profile',
    p11_moving:'Moving time', p11_pause:'Paused time', p11_desc:'Total descent',
    p11_max:'Max altitude', p11_min:'Min altitude', p11_avgspd:'Avg speed', p11_pace:'Avg pace',
    p11_cal:'Calories', p11_splits:'Splits (per km)', p11_nomk:'No markers',
    p11_src_alt:'Altitude source', p11_src_step:'Step source',

    p12_t:'Photo map', p12_sheet:'photos', p12_nogps:'No location',
    p12_empty_t:'No geotagged photos', p12_empty_d:'After granting photo access, photos with GPS will be clustered here by where they were taken.',

    p13_t:'View', p13_map:'Show on map',

    p14_t:'Settings', p14_about:'About', p14_ver:'Version',
    g_general:'General', g_alert:'Alerts', g_rec:'Recording', g_unit:'Units', g_map:'Map', g_data:'Data', g_about:'About',
    s_lang:'Language', s_lang_v:['Follow system','简体中文','English'],
    s_alert:'Master alert switch', s_voice:'Voice announcement', s_dalert:'Distance alert', s_dval:'Distance interval',
    s_aalert:'Altitude alert', s_aval:'Altitude interval', s_vibe:'Haptic feedback',
    s_locmode:'Location mode', s_locmode_v:['High accuracy','Battery saver'], s_autopause:'Auto pause',
    s_screenon:'Keep screen on', s_bg:'Background recording',
    s_udist:'Distance unit', s_udist_v:['km','mi'], s_ualt:'Altitude unit', s_ualt_v:['m','ft'],
    s_pace:'Pace display', s_pace_v:['Pace','Speed'],
    s_maptype:'Map type', s_maptype_v:['Standard','Satellite'], s_lw:'Track width', s_lw_v:['Thin','Medium','Thick'],
    s_photocl:'Show photo clusters',
    s_crs:'Export CRS', s_crs_v:['GCJ-02','WGS-84'], s_gpx:'Extra export format', s_gpx_v:['GPX','None'],
    s_media:'Include media in backup', s_cksum:'Include checksums', s_conflict:'Default import conflict policy',
    s_conflict_v:['Skip','Overwrite','Save as copy','Ask each time'],
    s_expsingle:'Export one trip / batch export', s_expzip:'Export full backup (ZIP)', s_impbtn:'Import data (JSON / ZIP)',
    s_alert_note:'Altitude interval is the only altitude threshold — it controls both how often alerts fire and how dense altitude markers are.',
    s_nobar:'On devices without a barometer the lower bound is raised to 30 m automatically.',

    p15_t:'About', p15_repo:'Repository', p15_lic:'License', p15_priv:'Privacy policy', p15_issue:'Report an issue',
    p15_desc:'A map-centric, all-in-one plan / record / review app for hikers. Data stays local and can be exported in full at any time.',

    p16_t:'Export', p16_prep:'Preparing…', p16_done:'Export complete', p16_open:'Open folder',
    p16_cancel_q:'Cancel the export?', p16_file:'File name',

    p17_t:'Import preview', p17_file:'File', p17_will:'To import', p17_conflict:'Conflicts',
    p17_crs_warn:'This file uses WGS-84. It will be converted to the internal CRS (GCJ-02) on import.',
    p17_policy:'Conflict policy', p17_start:'Start import', p17_onlyview:'Preview only',
    p17_cnt_t:'Trips', p17_cnt_r:'Planned routes', p17_cnt_m:'Media files', p17_range:'Time range',

    p18_t:'Import result', p18_ok:'Imported', p18_skip:'Skipped', p18_fail:'Failed', p18_reason:'Failures',
    p18_pol:'Skip (default)', p18_pol2:'Overwrite', p18_pol3:'Save as copy', p18_pol4:'Ask each time'
  }
};

/* 难度枚举（PRD v1.6：存枚举不存译文） */
var DIFF = { EASY:{zh:'轻松',en:'Easy'}, MODERATE:{zh:'中等',en:'Moderate'}, HARD:{zh:'困难',en:'Hard'},
             CHALLENGING:{zh:'挑战',en:'Challenging'} };

/* ---------------- 状态 ---------------- */
var S = {
  lang: 'zh',
  page: 'p01',
  perms: { loc:false, notif:false, sensor:false, media:false },
  plan: { start:null, end:null, picking:'start', q:'', mode:'auto', sel:0, wps:[], snap:'straight',
          name:'', note:'' },
  routes: [
    { id:'pr-001', name:'梧桐山 泰山涧→好汉坡', nameEn:'Wutongshan, Taishanjian → HaoHanPo',
      dist:8456, asc:650, desc:630, min:146, diff:'MODERATE', created:'2026-09-18', legs:true }
  ],
  trips: [],
  rec: { active:false, paused:false, sec:0, dist:0, asc:0, desc:0, steps:0, alt:120,
         markers:[], name:'梧桐山', note:'', lastD:0, lastA:0, lastDE:0 },
  cur: null,               // 当前查看的记录
  tab11: 'ov',
  selPhoto: 0,
  sheetCluster: null,
  exp: { running:false, pct:0, done:false },
  imp: { policy:'skip' },
  mapLayer: 'route',
  picking: false
};

/* 演示用样本记录 */
var SAMPLE = {
  id:'t1', name:'梧桐山', nameEn:'Wutongshan', date:'2026-09-19 07:12',
  dur:11820, moving:9980, paused:1840, dist:8234, asc:620, desc:616,
  max:944, min:120, steps:15230, kcal:850, altSrc:'BAROMETER_FUSED', stepSrc:'SENSOR_COUNTER',
  out:{d:4310,a:620,de:41,t:6377}, ret:{d:3924,a:39,de:575,t:5443},
  markers:[
    {t:'SUMMIT',  n:1, ts:'09:58', alt:944, extra:'', note:'大梧桐顶'},
    {t:'ALERT_ASCENT', n:3, ts:'08:47', alt:420, extra:'+300m', note:''},
    {t:'ALERT_DISTANCE', n:5, ts:'08:32', alt:311, extra:'5000m', note:''},
    {t:'MANUAL',  n:1, ts:'08:12', alt:186, extra:'', note:'补水点'}
  ]
};
S.trips = [SAMPLE];

/* 地图几何（百分比坐标） */
var TRACK = [[6,90],[13,85],[20,81],[27,76],[34,72],[41,67],[47,62],[53,57],[59,51],[65,46],[71,42],[78,45],[85,52],[91,60]];
var POIS = [
  {n:'梧桐山村', nEn:'Wutongshan Village', x:6,  y:90, k:'poi', a:'广东省深圳市罗湖区'},
  {n:'泰山涧',   nEn:'Taishan Stream',     x:34, y:72, k:'poi', a:'登山口 · Trailhead'},
  {n:'大梧桐顶', nEn:'Wutong Summit',      x:71, y:42, k:'poi', a:'海拔 943.7m · 943.7 m'},
  {n:'好汉坡',   nEn:'Haohan Slope',       x:59, y:51, k:'poi', a:'陡坡 · Steep section'},
  {n:'停车场',   nEn:'Parking',            x:13, y:85, k:'poi', a:'梧桐山村停车场'}
];
var CLUSTERS = [
  {x:20,y:81,n:7}, {x:41,y:67,n:3}, {x:59,y:51,n:12}, {x:71,y:42,n:18}, {x:85,y:52,n:5}
];

/* ---------------- 工具 ---------------- */
function L() { return S.lang; }
function t(k) { var d = STR[L()]; return (d && d[k] != null) ? d[k] : (STR.zh[k] != null ? STR.zh[k] : k); }
function esc(s) { return String(s == null ? '' : s).replace(/[&<>"]/g, function (c) {
  return { '&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;' }[c]; }); }
function nm(o) { return L() === 'en' && o.nameEn ? o.nameEn : o.name; }
function dif(d) { return DIFF[d] ? DIFF[d][L()] : d; }

function fmtDur(sec) {
  sec = Math.max(0, Math.round(sec));
  var h = Math.floor(sec / 3600), m = Math.floor(sec % 3600 / 60), s = sec % 60;
  var pad = function (v) { return v < 10 ? '0' + v : '' + v; };
  return h > 0 ? h + ':' + pad(m) + ':' + pad(s) : pad(m) + ':' + pad(s);
}
function fmtDurShort(sec) {
  var h = Math.floor(sec / 3600), m = Math.round(sec % 3600 / 60);
  if (L() === 'en') return h > 0 ? h + 'h' + m + 'm' : m + 'm';
  return h > 0 ? h + '小时' + m + '分' : m + '分钟';
}
function fmtDist(m) {
  if (S.settings.udist === 'mi') { var mi = m / 1609.344; return mi.toFixed(2) + ' ' + t('mi'); }
  return (m / 1000).toFixed(2) + ' ' + t('km');
}
function fmtAlt(m) {
  if (S.settings.ualt === 'ft') return Math.round(m * 3.28084) + ' ' + t('ft');
  return Math.round(m) + ' ' + t('m');
}
function fmtAltN(m) {
  if (S.settings.ualt === 'ft') return Math.round(m * 3.28084);
  return Math.round(m);
}
function altUnit() { return S.settings.ualt === 'ft' ? t('ft') : t('m'); }

/* 默认设置（PRD 6.7） */
S.settings = {
  lang:'zh', alert:true, voice:true, dalert:true, dval:100, aalert:true, aval:100, vibe:true,
  locmode:'high', autopause:false, screenon:true, bg:true,
  udist:'km', ualt:'m', pace:'pace',
  maptype:'std', lw:'mid', photocl:true,
  crs:'GCJ-02', gpx:'none', media:false, cksum:true, conflict:'skip'
};

/* ---------------- 地图绘制 ---------------- */
function contours() {
  var out = '';
  for (var i = 0; i < 13; i++) {
    var y0 = -6 + i * 30, d = 'M -8 ' + y0;
    for (var x = 0; x <= 108; x += 6) {
      var y = y0 + Math.sin((x + i * 26) / 22) * 13 + Math.sin((x + i * 11) / 10) * 5;
      d += ' L ' + x + ' ' + y.toFixed(1);
    }
    out += '<path d="' + d + '" fill="none" stroke="' + (i % 4 === 0 ? '#C9D9BB' : '#D8E4CC') +
           '" stroke-width="' + (i % 4 === 0 ? 1.4 : 0.9) + '"/>';
  }
  return '<svg class="contours" viewBox="0 0 100 340" preserveAspectRatio="none">' + out + '</svg>';
}
function poly(pts, color, w, dash) {
  if (!pts.length) return '';
  var d = pts.map(function (p, i) { return (i ? 'L' : 'M') + p[0] + ' ' + p[1]; }).join(' ');
  return '<path d="' + d + '" fill="none" stroke="' + color + '" stroke-width="' + w + '" ' +
         'stroke-linecap="round" stroke-linejoin="round" vector-effect="non-scaling-stroke"' +
         (dash ? ' stroke-dasharray="' + dash + '"' : '') + ' opacity=".92"/>';
}
function markerHTML(m) {
  var cls = '', inner = '';
  if (m.t === 'SUMMIT') { cls = 'summit'; inner = '<i class="flag"></i>'; }
  else if (m.t === 'ALERT_ASCENT') { cls = 'asc'; inner = '<i class="dot"></i>'; }
  else if (m.t === 'ALERT_DESCENT') { cls = 'desc'; inner = '<i class="dot"></i>'; }
  else if (m.t === 'ALERT_DISTANCE') { cls = 'dist'; inner = '<i class="dot"></i>'; }
  else if (m.t === 'MANUAL') { cls = 'manual'; inner = '<i class="drop"></i>'; }
  else if (m.t === 'WP') { cls = 'wp'; inner = '<i class="wp">' + m.n + '</i>'; }
  else if (m.t === 'START' || m.t === 'END') {
    cls = 'endpoint'; inner = '<i class="dot" style="background:' +
          (m.t === 'START' ? '#2F80ED' : '#1B4F9C') + ';width:14px;height:14px"></i>';
  }
  return '<div class="mk ' + cls + '" style="left:' + m.x + '%;top:' + m.y + '%">' + inner +
         (m.label ? '<span class="lbl">' + esc(m.label) + '</span>' : '') + '</div>';
}
function bluedot() { return '<div class="mk bluedot" style="left:6%;top:90%"><i></i></div>'; }

/* ---------------- 通用片段 ---------------- */
function appbar(title, rightHTML, backTo) {
  return '<div class="appbar">' +
    (backTo ? '<button class="iconbtn" data-go="' + backTo + '">' + ic('back') + '</button>'
            : '<span style="width:4px"></span>') +
    '<span>' + esc(title) + '</span><span class="sp"></span>' + (rightHTML || '') + '</div>';
}
function tabbar() {
  var items = [['p02','map',t('tab_map')], ['p07','route',t('tab_plan')],
               ['p10','hist',t('tab_hist')], ['p14','set',t('tab_me')]];
  return '<div class="tabbar">' + items.map(function (it) {
    return '<div class="tab' + (S.page === it[0] ? ' on' : '') + '" data-go="' + it[0] + '">' +
           ic(it[1]) + '<span>' + it[2] + '</span></div>';
  }).join('') + '</div>';
}
function miniTrack() {
  return '<svg viewBox="0 0 100 40" preserveAspectRatio="none">' +
    '<path d="' + TRACK.map(function (p, i) { return (i ? 'L' : 'M') + p[0] + ' ' + (p[1] - 40); }).join(' ') +
    '" fill="none" stroke="#E24B4A" stroke-width="3" stroke-linecap="round" vector-effect="non-scaling-stroke"/></svg>';
}
/* 底部操作区：两列等宽网格平铺；数量为奇数时，最后一个独占整行。
   统一入口，避免各页面手写时宽度不一致或挤在一起。
   list: [{label, icon, act|go, cls}] */
function acts(list) {
  var odd = list.length % 2 === 1;
  var h = '<div class="acts">';
  list.forEach(function (b, i) {
    var wide = (odd && i === list.length - 1);   // 奇数个时末位独占整行（n=1 即整行）
    h += '<button class="btn ' + (b.cls || 'gray') + (wide ? ' wide' : '') + '"' +
      (b.go ? ' data-go="' + b.go + '"' : '') +
      (b.act ? ' data-act="' + b.act + '"' : '') +
      (b.data ? ' ' + b.data : '') + '>' +
      (b.icon ? ic(b.icon) : '') + '<span>' + esc(b.label) + '</span></button>';
  });
  return h + '</div>';
}
function toast(msg) {
  var el = document.getElementById('toast');
  el.textContent = msg; el.classList.add('show');
  clearTimeout(el._t); el._t = setTimeout(function () { el.classList.remove('show'); }, 2400);
}
function dialog(title, msg, btns) {
  var b = btns.map(function (x) {
    return '<button class="' + (x.cls || '') + '" data-act="' + x.act + '">' + esc(x.label) + '</button>';
  }).join('');
  return '<div class="dlg"><div class="box"><div class="t">' + esc(title) + '</div>' +
    (msg ? '<div class="m">' + msg + '</div>' : '') + '<div class="b">' + b + '</div></div></div>';
}

/* ================= 页面 ================= */
var pages = {};

/* P-01 权限引导 */
pages.p01 = function () {
  var items = [
    ['loc', t('p01_loc'), t('p01_loc_d'), true],
    ['notif', t('p01_notif'), t('p01_notif_d'), false],
    ['sensor', t('p01_sensor'), t('p01_sensor_d'), false],
    ['media', t('p01_media'), t('p01_media_d'), false]
  ];
  return '<div class="page">' +
    '<div class="pad" style="padding-top:34px;text-align:center">' +
      '<div style="width:64px;height:64px;margin:0 auto 14px;color:#2F80ED">' + ic('mtn') + '</div>' +
      '<div style="font-size:22px;font-weight:700">' + t('app') + '</div>' +
      '<div style="font-size:13px;color:var(--text-2);margin-top:6px">GoHiking</div>' +
    '</div>' +
    '<div class="sec-title">' + t('p01_t') + '</div>' +
    '<div class="hint" style="padding-bottom:12px">' + t('p01_s') + '</div>' +
    '<div class="card">' + items.map(function (it) {
      var on = S.perms[it[0]];
      return '<div class="rowline"><div class="lb"><div class="t">' + it[1] +
        (it[3] ? ' <span class="tag r" style="margin-left:4px">' + t('p01_need') + '</span>' : '') +
        '</div><div class="s">' + it[2] + '</div></div>' +
        '<button class="btn gray sm" data-act="perm" data-k="' + it[0] + '"' +
        (on ? ' disabled' : '') + '>' +
        (on ? ic('check') + t('allowed') : t('allow')) + '</button></div>';
    }).join('') + '</div>' +
    acts([{ label:t('p01_all'), act:'permall', cls:'pri' },
           { label:t('p01_skip'), go:'p02' }]) +
  '</div>';
};

/* P-02 首页地图 */
pages.p02 = function () {
  var hasPlan = S.routes.length > 0;
  var mk = [];
  if (hasPlan) {
    mk.push({t:'START', x:6, y:90, label:t('p03_start')});
    mk.push({t:'END', x:71, y:42, label:'大梧桐顶'});
  }
  if (S.rec.markers.length && S.page === 'p02') { /* 记录结束后回到首页不显示 */ }
  return '<div class="page">' +
    '<div class="map">' + contours() +
      '<svg class="lines" viewBox="0 0 100 100" preserveAspectRatio="none">' +
        (hasPlan ? poly(TRACK.slice(0, 11), '#2F80ED', 7, null) : '') +
        (hasPlan ? poly(TRACK.slice(10), '#1B4F9C', 7, '10 7') : '') +
      '</svg>' +
      POIS.map(function (p) {
        return '<div class="poilbl" style="left:' + p.x + '%;top:' + p.y + '%" data-act="poi" data-n="' +
          esc(L() === 'en' ? p.nEn : p.n) + '" data-x="' + p.x + '" data-y="' + p.y + '">' +
          esc(L() === 'en' ? p.nEn : p.n) + '</div>';
      }).join('') +
      mk.map(markerHTML).join('') + bluedot() +
      '<div class="searchbar"><div class="searchbox">' + ic('search') +
        '<input data-act="home-search" placeholder="' + t('p03_ph') + '" readonly></div>' +
        '<button class="mapbtn" data-go="p12" title="' + t('p12_t') + '">' + ic('img') + '</button></div>' +
      '<div class="mapbtns">' +
        '<button class="mapbtn" data-act="nop" title="layers">' + ic('layers') + '</button>' +
        '<button class="mapbtn" data-act="nop">' + ic('plus') + '</button>' +
        '<button class="mapbtn" data-act="nop">' + ic('locate') + '</button>' +
      '</div>' +
      '<div style="position:absolute;left:12px;bottom:16px;font-size:10px;color:#5A616B;' +
        'background:rgba(255,255,255,.86);padding:4px 8px;border-radius:6px">' + t('p02_hint') + '</div>' +
    '</div>' +
    '<div class="homebar">' +
      '<button class="btn gray" data-go="p07">' + ic('route') + '<span>' + t('p02_plan') + '</span></button>' +
      '<button class="btn red" data-act="startrec">' + ic('play') + '<span>' + t('p02_rec') + '</span></button>' +
    '</div>' +
  '</div>';
};

/* P-03 选点页 */
var SUGG = [
  {n:'梧桐山', a:'广东省深圳市罗湖区 · 山峰', k:'poi', x:71, y:42},
  {n:'梧桐山村', a:'广东省深圳市罗湖区 · 村庄', k:'poi', x:6, y:90},
  {n:'泰山涧登山口', a:'距您 3.2 km · 登山口', k:'poi', x:34, y:72},
  {n:'梧桐山停车场', a:'距您 3.4 km · 停车场', k:'poi', x:13, y:85},
  {n:'好汉坡', a:'距您 5.8 km · 陡坡路段', k:'poi', x:59, y:51},
  {n:'M364 路', a:'公交线路', k:'bus', x:0, y:0},
  {n:'梧桐', a:'常用搜索词', k:'kw', x:0, y:0}
];
pages.p03 = function () {
  var p = S.plan;
  var q = p.q.trim();
  var list = q ? SUGG.filter(function (s) { return s.n.indexOf(q) >= 0 || s.a.indexOf(q) >= 0; }) : SUGG.slice(0, 4);
  var cur = p.picking === 'start' ? p.start : p.end;
  var mk = [];
  if (p.start) mk.push({t:'START', x:p.start.x, y:p.start.y, label:t('p03_start')});
  if (p.end) mk.push({t:'END', x:p.end.x, y:p.end.y, label:t('p03_end')});
  if (p.start && p.end) {
    mk = [];
    mk.push({t:'START', x:p.start.x, y:p.start.y, label:t('p03_start')});
    mk.push({t:'END', x:p.end.x, y:p.end.y, label:t('p03_end')});
  }
  return '<div class="page">' +
    appbar(t('p03_t'), '<button class="iconbtn" data-act="swap">' + ic('refresh') + '</button>', 'p07') +
    '<div class="map">' + contours() +
      '<svg class="lines" viewBox="0 0 100 100" preserveAspectRatio="none">' +
        (p.start && p.end ? poly([[p.start.x, p.start.y], [p.end.x, p.end.y]], '#2F80ED', 6, '8 6') : '') +
      '</svg>' +
      POIS.map(function (o) {
        return '<div class="poilbl" style="left:' + o.x + '%;top:' + o.y + '%" data-act="poi" data-n="' +
          esc(L() === 'en' ? o.nEn : o.n) + '" data-x="' + o.x + '" data-y="' + o.y + '">' +
          esc(L() === 'en' ? o.nEn : o.n) + '</div>';
      }).join('') +
      mk.map(markerHTML).join('') + bluedot() +
      '<div class="searchbar"><div class="searchbox">' + ic('search') +
        '<input id="q" value="' + esc(p.q) + '" placeholder="' + t('p03_ph') +
        '" data-act="q" autocomplete="off"></div></div>' +
      (q ? '<div class="sugg">' + (list.length ? list.map(function (s) {
            var kind = s.k === 'poi' ? '<span class="tag b">' + t('p03_tip_poi') + '</span>'
                     : s.k === 'bus' ? '<span class="tag n">' + t('p03_tip_bus') + '</span>'
                     : '<span class="tag o">' + t('p03_tip_kw') + '</span>';
            return '<div class="si" data-act="pickpoi" data-x="' + s.x + '" data-y="' + s.y +
              '" data-n="' + esc(s.n) + '">' + ic(s.k === 'bus' ? 'warn' : 'locate') +
              '<div><div class="n">' + esc(s.n) + '</div><div class="a">' + esc(s.a) + '</div></div>' +
              '<span class="k">' + kind + '</span></div>';
          }).join('') : '<div class="si"><div><div class="n">' + t('p05_need') +
              '</div><div class="a">' + t('p03_manual') + '</div></div></div>') + '</div>' : '') +
      '<div class="mapbtns"><button class="mapbtn" data-act="nop">' + ic('locate') + '</button></div>' +
      '<div class="pickbar">' +
        '<div class="seg" style="margin-bottom:10px">' +
          '<button class="' + (p.picking === 'start' ? 'on' : '') + '" data-act="pickmode" data-v="start">' +
            t('p03_start') + '</button>' +
          '<button class="' + (p.picking === 'end' ? 'on' : '') + '" data-act="pickmode" data-v="end">' +
            t('p03_end') + '</button>' +
        '</div>' +
        '<div class="pt">' + (p.picking === 'start' ? t('p03_start') : t('p03_end')) + ' · ' + t('p03_pick') + '</div>' +
        '<div class="pv">' + (cur ? esc(cur.n) : '<span style="color:var(--text-3)">—</span>') + '</div>' +
        '<div style="display:flex;gap:8px">' +
          '<button class="btn gray sm" data-act="useloc">' + ic('locate') + t('p03_cur') + '</button>' +
          '<button class="btn pri sm" style="flex:1" data-act="torec">' + t('p03_next') + '</button>' +
        '</div>' +
        '<div style="margin-top:8px;text-align:center"><button class="btn ghost sm" data-act="tomanual">' +
          t('p04_manual') + '</button></div>' +
      '</div>' +
    '</div>' +
  '</div>';
};

/* P-04 推荐列表 */
pages.p04 = function () {
  var cands = [
    {k:'p04_a', d:8456, m:146, a:650, diff:'MODERATE'},
    {k:'p04_b', d:7120, m:132, a:742, diff:'HARD'},
    {k:'p04_c', d:10240, m:175, a:498, diff:'MODERATE'}
  ];
  var sel = S.plan.sel;
  return '<div class="page">' +
    appbar(t('p04_t'), '', 'p03') +
    '<div class="map fixed" style="height:190px">' + contours() +
      '<svg class="lines" viewBox="0 0 100 100" preserveAspectRatio="none">' +
        poly(TRACK.slice(0, 11), '#2F80ED', sel === 0 ? 7 : 4, null) +
        poly(TRACK.slice(10), '#1B4F9C', sel === 0 ? 7 : 4, '9 6') +
        poly([[6,90],[10,80],[20,74],[30,66],[42,58],[55,50],[68,44],[71,42]], '#93B4E8', 4, null) +
        poly([[6,90],[8,88],[14,86],[22,84],[30,80],[38,78],[46,74],[54,68],[62,60],[68,50],[71,42]],
             '#B9CBD8', 4, null) +
      '</svg>' + bluedot() +
    '</div>' +
    '<div class="hint">' + t('p04_src') + '</div>' +
    '<div style="padding:10px 12px 4px">' + cands.map(function (c, i) {
      var on = i === sel;
      return '<div class="card" style="margin:0 0 10px;border:2px solid ' +
        (on ? 'var(--primary)' : 'transparent') + ';cursor:pointer" data-act="selcand" data-i="' + i + '">' +
        '<div class="rowline"><div class="lb"><div class="t">' + t(c.k) +
          ' <span class="tag ' + (c.diff === 'HARD' ? 'o' : 'g') + '" style="margin-left:6px">' + dif(c.diff) + '</span>' +
        '</div>' +
        '<div class="kv" style="display:flex;gap:14px;font-size:12px;color:var(--text-2);margin-top:6px">' +
          '<span>' + t('p04_dist') + ' <b style="color:var(--text)">' + fmtDist(c.d) + '</b></span>' +
          '<span>' + t('p04_est') + ' <b style="color:var(--text)">' + fmtDurShort(c.m * 60) + '</b></span>' +
          '<span>' + t('p04_asc') + ' <b style="color:var(--text)">' + fmtAltN(c.a) + ' ' + altUnit() + '</b>' +
            ' <span style="color:var(--text-2)">' + t('p04_est_tag') + '</span></span>' +
        '</div></div>' +
        (on ? '<span style="color:var(--primary)">' + ic('check') + '</span>' : '') +
        '</div></div>';
    }).join('') + '</div>' +
    acts([{ label:t('p04_manual'), act:'tomanual' },
           { label:t('p04_re'), act:'nop', icon:'refresh' },
           { label:t('p04_choose'), go:'p06', cls:'pri' }]) +
  '</div>';
};

/* P-05 手动打途经点 */
pages.p05 = function () {
  var wps = S.plan.wps;
  var pts = wps.length ? wps : [];
  return '<div class="page">' +
    appbar(t('p05_t'), '', 'p03') +
    '<div class="map">' + contours() +
      '<svg class="lines" viewBox="0 0 100 100" preserveAspectRatio="none">' +
        (pts.length > 1 ? poly(pts, '#2F80ED', 6, S.plan.snap === 'straight' ? '9 6' : null) : '') +
      '</svg>' +
      '<div data-act="addwp" style="position:absolute;inset:0;z-index:1"></div>' +
      wps.map(function (w, i) { return markerHTML({t:'WP', x:w.x, y:w.y, n:i + 1}); }).join('') +
      '<div class="mapbtns"><button class="mapbtn" data-act="nop">' + ic('locate') + '</button></div>' +
      '<div class="pickbar">' +
        '<div class="pt">' + t('p05_hint') + '</div>' +
        '<div class="pv">' + t('p05_n') + ' ' + wps.length + ' / 50' +
          (L() === 'en' ? '' : ' 个') + '</div>' +
        '<div class="seg" style="margin-bottom:10px">' +
          '<button class="' + (S.plan.snap === 'straight' ? 'on' : '') + '" data-act="snap" data-v="straight">' +
            t('p05_straight') + '</button>' +
          '<button class="' + (S.plan.snap === 'snap' ? 'on' : '') + '" data-act="snap" data-v="snap">' +
            t('p05_snap') + '</button>' +
        '</div>' +
        '<div style="display:flex;gap:8px">' +
          '<button class="btn gray sm" data-act="undowp"' + (wps.length ? '' : ' disabled') + '>' +
            ic('refresh') + t('p05_undo') + '</button>' +
          '<button class="btn pri sm" style="flex:1" data-act="finishwp"' + (wps.length >= 2 ? '' : ' disabled') + '>' +
            t('p05_finish') + '</button>' +
        '</div>' +
      '</div>' +
    '</div>' +
  '</div>';
};

/* P-06 保存计划 */
pages.p06 = function () {
  var p = S.plan;
  var name = p.name || (p.end ? p.end.n : '梧桐山');
  return '<div class="page">' +
    appbar(t('p06_t'), '', 'p04') +
    '<div class="pad">' +
      '<div class="sec-title" style="padding:6px 2px 6px">' + t('p06_name') + '</div>' +
      '<input class="inp" id="pname" value="' + esc(name) + '" data-act="pname">' +
      '<div class="sec-title" style="padding:16px 2px 6px">' + t('p06_note') + '</div>' +
      '<textarea class="inp" id="pnote" data-act="pnote" placeholder="' +
        (L() === 'en' ? 'e.g. Up via Taishanjian, summit via HaohanPo' : '从梧桐山村上山，好汉坡登顶') + '">' +
        esc(p.note) + '</textarea>' +
      '<div class="sec-title" style="padding:16px 2px 6px">' + t('p06_sum') + '</div>' +
      '<div class="card" style="margin:0">' +
        '<div class="rowline"><div class="lb"><div class="t">' + t('p11_out') + '</div></div>' +
          '<div class="vl">4.31 km · ' + fmtAltN(625) + ' ' + altUnit() + ' ' +
          '<span style="color:var(--text-2)">' + t('p04_est_tag') + '</span> · 1h16m</div></div>' +
        '<div class="rowline"><div class="lb"><div class="t">' + t('p11_ret') + '</div></div>' +
          '<div class="vl">4.05 km · ' + fmtAltN(600) + ' ' + altUnit() + ' ' +
          '<span style="color:var(--text-2)">' + t('p04_est_tag') + '</span> · 1h10m</div></div>' +
        '<div class="rowline"><div class="lb"><div class="t">' + t('p04_diff') + '</div></div>' +
          '<div class="vl"><span class="tag g">' + dif('MODERATE') + '</span></div></div>' +
      '</div>' +
    '</div>' +
    acts([{ label:t('save'), act:'saveroute', cls:'pri' }]) +
  '</div>';
};

/* P-07 计划线路列表 */
pages.p07 = function () {
  if (!S.routes.length) {
    return '<div class="page">' + appbar(t('p07_t'), '<button class="iconbtn" data-go="p03">' +
      ic('plus') + '</button>') +
      '<div class="empty"><div class="ic">' + ic('route') + '</div>' +
      '<div class="t">' + t('p07_empty_t') + '</div><div class="d">' + t('p07_empty_d') + '</div>' +
      '<div style="margin-top:22px"><button class="btn pri" data-go="p03">' + ic('plus') + t('p07_new') + '</button></div>' +
      '</div></div>';
  }
  return '<div class="page">' +
    appbar(t('p07_t'), '<button class="iconbtn" data-go="p03">' + ic('plus') + '</button>') +
    '<div style="padding:12px"><button class="btn pri block" data-go="p03">' + ic('plus') + t('p07_new') + '</button></div>' +
    '<div class="card">' + S.routes.map(function (r, i) {
      return '<div class="list-item" data-act="openroute" data-i="' + i + '">' +
        '<div class="thumb"><svg viewBox="0 0 100 100" preserveAspectRatio="none">' +
          '<path d="M8 92 L30 74 L48 62 L62 50 L74 42 L88 52" fill="none" stroke="#2F80ED" ' +
          'stroke-width="7" stroke-linecap="round" vector-effect="non-scaling-stroke"/>' +
          '<path d="M74 42 L86 56 L92 70" fill="none" stroke="#1B4F9C" stroke-width="7" ' +
          'stroke-dasharray="9 7" stroke-linecap="round" vector-effect="non-scaling-stroke"/></svg></div>' +
        '<div class="main"><div class="nm">' + esc(nm(r)) + '</div>' +
        '<div class="sub">' + r.created + '</div>' +
        '<div class="kv"><span>' + t('p04_dist') + ' <b>' + fmtDist(r.dist) + '</b></span>' +
        '<span>' + t('p04_asc') + ' <b>' + fmtAltN(r.asc) + ' ' + altUnit() + '</b>' +
          ' <span style="color:var(--text-2)">' + t('p04_est_tag') + '</span></span>' +
        '<span>' + t('p04_diff') + ' <b>' + dif(r.diff) + '</b></span></div></div>' +
        ic('chevR', 'chev') + '</div>';
    }).join('') + '</div>' +
  '</div>';
};

/* P-08 记录页 */
pages.p08 = function () {
  var r = S.rec;
  var prog = Math.min(1, r.sec / 2400);
  var cut = Math.max(2, Math.round(TRACK.length * (0.15 + prog * 0.85)));
  var pts = TRACK.slice(0, cut);
  var alertOn = S.settings.alert;
  return '<div class="page">' +
    '<div class="appbar">' +
      '<button class="iconbtn" data-act="backrec">' + ic('back') + '</button>' +
      '<span>' + esc(r.name) + '</span><span class="sp"></span>' +
      '<span class="tag ' + (alertOn ? 'b' : 'n') + '" data-act="togglealert" style="cursor:pointer">' +
        t('p08_alert') + ': ' + (alertOn ? t('p08_on') : t('p08_off')) + '</span>' +
    '</div>' +
    '<div class="map">' + contours() +
      '<svg class="lines" viewBox="0 0 100 100" preserveAspectRatio="none">' +
        poly(TRACK.slice(0, 11), '#2F80ED', 6, null) +
        poly(pts, '#E24B4A', 7, null) +
      '</svg>' +
      POIS.map(function (o) {
        return '<div class="poilbl" style="left:' + o.x + '%;top:' + o.y + '%">' +
          esc(L() === 'en' ? o.nEn : o.n) + '</div>';
      }).join('') +
      r.markers.map(function (m) { return markerHTML({t:m.t, x:m.x, y:m.y, n:m.n}); }).join('') +
      '<div class="mk" style="left:' + pts[pts.length - 1][0] + '%;top:' + pts[pts.length - 1][1] + '%">' +
        '<i class="dot" style="background:#E24B4A;width:14px;height:14px"></i></div>' +
      '<div class="mapbtns"><button class="mapbtn" data-act="nop">' + ic('locate') + '</button></div>' +
      (r.paused ? '<div style="position:absolute;top:14px;left:50%;transform:translateX(-50%);' +
        'background:rgba(27,31,36,.86);color:#fff;padding:6px 14px;border-radius:999px;font-size:12px;z-index:8">' +
        t('p08_paused') + '</div>' : '') +
    '</div>' +
    '<div class="recstats">' +
      '<div class="grid">' +
        '<div class="cell"><div class="big" id="rDist">' + (r.dist / 1000).toFixed(2) + '</div>' +
          '<div class="cap">' + t('p08_dist') + ' (' + t('km') + ')</div></div>' +
        '<div class="cell"><div class="big" id="rTime">' + fmtDur(r.sec) + '</div>' +
          '<div class="cap">' + t('p08_time') + '</div></div>' +
      '</div>' +
      '<div class="div"></div>' +
      '<div class="grid2">' +
        '<div class="cell"><div class="big" id="rAlt">' + fmtAltN(r.alt) +
          '<span style="font-size:11px;color:var(--text-2)"> ' + altUnit() + '</span></div>' +
          '<div class="cap">' + t('p08_alt') +
          (S.settings.ualt === 'm' ? ' <span class="tag o" style="font-size:9px">' + t('p08_gps') + '</span>' : '') +
          '</div></div>' +
        '<div class="cell"><div class="big" id="rAsc">+' + fmtAltN(r.asc) + '</div>' +
          '<div class="cap">' + t('p08_asc') + '</div></div>' +
        '<div class="cell"><div class="big" id="rSteps">' + r.steps + '</div>' +
          '<div class="cap">' + t('p08_steps') + '</div></div>' +
      '</div>' +
    '</div>' +
    '<div class="recctrl">' +
      '<button data-act="mark">' + ic('drop') + '<span>' + t('p08_mark') + '</span></button>' +
      '<button class="main" data-act="pause">' + ic(r.paused ? 'play' : 'pause') +
        '<span>' + (r.paused ? t('p08_resume') : t('p08_pause')) + '</span></button>' +
      '<button data-act="summit">' + ic('flag') + '<span>' + t('p08_summit') + '</span></button>' +
      '<button class="stop" data-act="stoprec">' + ic('stop') + '<span>' + t('p08_stop') + '</span></button>' +
    '</div>' +
  '</div>';
};

/* P-09 记录保存 */
pages.p09 = function () {
  var r = S.rec;
  return '<div class="page">' +
    appbar(t('p09_t'), '', 'p08') +
    '<div class="pad">' +
      '<div class="card" style="margin:0 0 14px">' +
        '<div class="summary">' +
          '<div class="c"><div class="v">' + (r.dist / 1000).toFixed(2) + '</div><div class="k">' + t('km') + '</div></div>' +
          '<div class="c"><div class="v">' + fmtDur(r.sec).slice(0, 5) + '</div><div class="k">' + t('p08_time') + '</div></div>' +
          '<div class="c"><div class="v">+' + fmtAltN(r.asc) + '</div><div class="k">' + t('p08_asc') + '</div></div>' +
          '<div class="c"><div class="v">' + r.steps + '</div><div class="k">' + t('p08_steps') + '</div></div>' +
        '</div>' +
      '</div>' +
      '<div class="sec-title" style="padding:6px 2px 6px">' + t('p09_name') + '</div>' +
      '<input class="inp" value="' + esc(r.name) + '" data-act="rname">' +
      '<div class="sec-title" style="padding:16px 2px 6px">' + t('p09_note') + '</div>' +
      '<textarea class="inp" data-act="rnote"></textarea>' +
    '</div>' +
    acts([{ label:t('p09_discard'), act:'discard' },
           { label:t('save'), act:'savetrip', cls:'pri' }]) +
  '</div>';
};

/* P-10 运动记录列表 */
pages.p10 = function () {
  if (!S.trips.length) {
    return '<div class="page">' + appbar(t('p10_t')) +
      '<div class="empty"><div class="ic">' + ic('mtn') + '</div>' +
      '<div class="t">' + t('p10_empty_t') + '</div><div class="d">' + t('p10_empty_d') + '</div>' +
      '<div style="margin-top:22px"><button class="btn pri" data-go="p02">' + ic('play') + t('p02_rec') + '</button></div>' +
      '</div></div>';
  }
  var tot = S.trips.reduce(function (a, x) {
    a.d += x.dist; a.t += x.dur; a.a += x.asc; return a; }, {d:0,t:0,a:0});
  return '<div class="page">' + appbar(t('p10_t')) +
    '<div class="card" style="margin:12px"><div class="summary">' +
      '<div class="c"><div class="v">' + S.trips.length + '</div><div class="k">' + t('p10_tt') + ' ' + t('p10_times') + '</div></div>' +
      '<div class="c"><div class="v">' + (tot.d / 1000).toFixed(1) + '</div><div class="k">' + t('km') + '</div></div>' +
      '<div class="c"><div class="v">' + Math.round(tot.t / 3600) + 'h</div><div class="k">' + t('p08_time') + '</div></div>' +
      '<div class="c"><div class="v">' + fmtAltN(tot.a) + '</div><div class="k">' + t('p08_asc') + '</div></div>' +
    '</div></div>' +
    '<div class="card">' + S.trips.map(function (x, i) {
      return '<div class="list-item" data-act="opentrip" data-i="' + i + '">' +
        '<div class="thumb">' + miniTrack() + '</div>' +
        '<div class="main"><div class="nm">' + esc(nm(x)) + '</div>' +
        '<div class="sub">' + x.date + '</div>' +
        '<div class="kv"><span>' + fmtDurShort(x.dur) + '</span><span>' + fmtDist(x.dist) + '</span>' +
        '<span>' + x.steps + ' ' + t('step') + '</span><span>+' + fmtAltN(x.asc) + ' ' + altUnit() + '</span></div>' +
        '</div>' + ic('chevR', 'chev') + '</div>';
    }).join('') + '</div>' +
  '</div>';
};

/* P-11 记录详情 */
pages.p11 = function () {
  var x = S.cur || SAMPLE;
  var tab = S.tab11;
  var body = '';
  if (tab === 'ov') {
    body = '<div class="card" style="margin:12px"><div class="statgrid">' +
      [[fmtDist(x.dist), t('p08_dist')], [fmtDur(x.dur), t('p08_time')],
       [fmtDur(x.moving), t('p11_moving')], [fmtDur(x.paused), t('p11_pause')],
       ['+' + fmtAltN(x.asc), t('p08_asc')], ['-' + fmtAltN(x.desc), t('p11_desc')],
       [fmtAltN(x.max), t('p11_max')], [fmtAltN(x.min), t('p11_min')],
       [(x.dist / x.moving * 3.6).toFixed(1) + ' km/h', t('p11_avgspd')],
       [Math.round(x.moving / (x.dist / 1000) / 60) + ':' + String(Math.round(x.moving / (x.dist / 1000) % 60)).padStart(2, '0') + '/km', t('p11_pace')],
       [x.steps, t('p08_steps')], [x.kcal + ' ' + t('kcal'), t('p11_cal')]
      ].map(function (c) {
        return '<div class="c"><div class="v">' + c[0] + '</div><div class="k">' + c[1] + '</div></div>';
      }).join('') + '</div></div>' +
      '<div class="sec-title">' + t('p11_out') + ' / ' + t('p11_ret') + '</div>' +
      '<div class="card"><div class="rowline"><div class="lb"><div class="t">' +
        '<span class="tag b">' + t('p11_out') + '</span></div></div>' +
        '<div class="vl">' + fmtDist(x.out.d) + ' · +' + fmtAltN(x.out.a) + ' ' + altUnit() +
        ' · ' + fmtDurShort(x.out.t) + '</div></div>' +
      '<div class="rowline"><div class="lb"><div class="t"><span class="tag b" style="background:#DCE6F7;color:#1B4F9C">' +
        t('p11_ret') + '</span></div></div>' +
        '<div class="vl">' + fmtDist(x.ret.d) + ' · -' + fmtAltN(x.ret.de) + ' ' + altUnit() +
        ' · ' + fmtDurShort(x.ret.t) + '</div></div></div>' +
      '<div class="sec-title">' + t('p11_src_alt') + ' / ' + t('p11_src_step') + '</div>' +
      '<div class="card"><div class="rowline"><div class="lb"><div class="t">' + t('p11_src_alt') + '</div></div>' +
        '<div class="vl"><span class="tag g">' + x.altSrc + '</span></div></div>' +
      '<div class="rowline"><div class="lb"><div class="t">' + t('p11_src_step') + '</div></div>' +
        '<div class="vl"><span class="tag b">' + x.stepSrc + '</span></div></div></div>';
  } else if (tab === 'ch') {
    var vals = [];
    for (var i = 0; i <= 60; i++) {
      var f = i / 60;
      vals.push(120 + 824 * Math.sin(Math.PI * Math.pow(f, 0.82)) * (1 - f * 0.06));
    }
    var pk = Math.max.apply(null, vals), pi = vals.indexOf(pk);
    var W = 340, H = 130, maxA = 950;
    var d = vals.map(function (v, i) {
      return (i ? 'L' : 'M') + (i / 60 * W).toFixed(1) + ' ' + (H - v / maxA * H).toFixed(1);
    }).join(' ');
    body = '<div class="sec-title">' + t('p11_altchart') + '</div>' +
      '<div class="card"><div class="chartbox"><svg viewBox="0 0 ' + W + ' ' + (H + 18) + '">' +
        '<defs><linearGradient id="ag" x1="0" y1="0" x2="0" y2="1">' +
        '<stop offset="0" stop-color="#2F80ED" stop-opacity=".26"/>' +
        '<stop offset="1" stop-color="#2F80ED" stop-opacity="0"/></linearGradient></defs>' +
        '<path d="' + d + ' L ' + W + ' ' + H + ' L 0 ' + H + ' Z" fill="url(#ag)"/>' +
        '<path d="' + d + '" fill="none" stroke="#2F80ED" stroke-width="2.2" stroke-linejoin="round"/>' +
        '<circle cx="' + (W * pi / 60).toFixed(1) + '" cy="' + (H - pk / maxA * H).toFixed(1) +
        '" r="4" fill="#fff" stroke="#E24B4A" stroke-width="2.4"/>' +
        '<text x="4" y="' + (H + 14) + '" font-size="10" fill="#9CA3AF">0 km</text>' +
        '<text x="' + (W - 34) + '" y="' + (H + 14) + '" font-size="10" fill="#9CA3AF">' +
          (x.dist / 1000).toFixed(1) + ' km</text>' +
        '<text x="4" y="12" font-size="10" fill="#9CA3AF">' + fmtAltN(Math.round(pk)) + '</text>' +
      '</svg></div>' +
      '<div class="chartlg"><span><i style="background:#2F80ED"></i>' + t('p08_alt') + '</span>' +
      '<span><i style="background:#E24B4A"></i>' + fmtAltN(Math.round(pk)) + ' ' + altUnit() + ' @ ' +
        (x.dist / 1000 * pi / 60).toFixed(1) + ' km</span></div>' +
      '</div>';
  } else if (tab === 'sp') {
    var rows = '';
    for (var k = 1; k <= 8; k++) {
      var gain = [82, 146, 118, 96, 74, 58, 31, 15][k - 1] || 0;
      var dur = [1320, 1485, 1240, 1510, 1180, 1420, 980, 845][k - 1] || 0;
      rows += '<tr><td>' + k + ' km</td><td>' + fmtDur(dur) + '</td><td>' +
        Math.floor(dur / 60) + ':' + String(dur % 60).padStart(2, '0') + '/km</td><td>+' + gain + '</td></tr>';
    }
    body = '<div class="sec-title">' + t('p11_splits') + '</div><div class="card">' +
      '<table class="splittable"><thead><tr><th>' + t('p08_dist') + '</th><th>' + t('p08_time') +
      '</th><th>' + t('p11_pace') + '</th><th>' + t('p08_asc') + '</th></tr></thead><tbody>' +
      rows + '</tbody></table></div>';
  } else if (tab === 'mk') {
    var ICN = { SUMMIT:'flag', ALERT_ASCENT:'up', ALERT_DESCENT:'down', ALERT_DISTANCE:'route', MANUAL:'drop' };
    var CLR = { SUMMIT:'r', ALERT_ASCENT:'g', ALERT_DESCENT:'o', ALERT_DISTANCE:'b', MANUAL:'o' };
    var LB = { SUMMIT:{zh:'登顶点',en:'Summit'}, ALERT_ASCENT:{zh:'海拔提醒 · 爬升',en:'Altitude · ascent'},
               ALERT_DESCENT:{zh:'海拔提醒 · 下降',en:'Altitude · descent'},
               ALERT_DISTANCE:{zh:'距离提醒',en:'Distance alert'}, MANUAL:{zh:'手动标记',en:'Manual'} };
    body = '<div class="card">' + x.markers.map(function (m) {
      return '<div class="rowline"><div class="lb"><div class="t">' +
        '<span class="tag ' + CLR[m.t] + '">' + LB[m.t][L()] + '</span> ' +
        (m.note ? esc(m.note) : '#' + m.n) + '</div>' +
        '<div class="s">' + m.ts + ' · ' + t('p08_alt') + ' ' + fmtAltN(m.alt) + ' ' + altUnit() +
        (m.extra ? ' · ' + m.extra : '') + '</div></div>' +
        '<span style="color:var(--text-3)">' + ic(ICN[m.t]) + '</span></div>';
    }).join('') + '</div>';
  } else {
    body = '<div class="sec-title">' + t('p11_ph') + '</div><div class="card"><div class="photostrip">' +
      photoStrip(0) + '</div></div>' +
      '<div class="hint">' + (L() === 'en' ? 'Photos taken during this trip (±30 min, within 500 m of the track).'
        : '本次记录期间拍摄的照片（时间 ±30 分钟，位置在轨迹 500m 范围内）。') + '</div>';
  }
  return '<div class="page">' +
    appbar(nm(x), '<button class="iconbtn" data-act="nop">' + ic('dots') + '</button>', 'p10') +
    '<div class="map fixed" style="height:170px">' + contours() +
      '<svg class="lines" viewBox="0 0 100 100" preserveAspectRatio="none">' + poly(TRACK, '#E24B4A', 7, null) + '</svg>' +
      markerHTML({t:'SUMMIT', x:71, y:42}) + markerHTML({t:'ALERT_ASCENT', x:47, y:62}) +
      markerHTML({t:'ALERT_DISTANCE', x:27, y:76}) + markerHTML({t:'MANUAL', x:20, y:81}) +
      markerHTML({t:'ALERT_DESCENT', x:60, y:52}) +
    '</div>' +
    '<div class="tabs">' +
      [['ov', t('p11_ov')], ['ch', t('p11_ch')], ['sp', t('p11_sp')], ['mk', t('p11_mk')], ['ph', t('p11_ph')]]
      .map(function (b) {
        return '<button class="' + (tab === b[0] ? 'on' : '') + '" data-act="tab11" data-v="' + b[0] + '">' +
          b[1] + '</button>';
      }).join('') +
    '</div>' +
    '<div style="padding-bottom:8px">' + body + '</div>' +
    acts([{ label:t('exp'), go:'p16', cls:'pri', icon:'exp' },
           { label:t('share'), act:'nop', icon:'share' },
           { label:t('rename'), act:'nop', icon:'edit' },
           { label:t('del'), act:'nop', icon:'trash' }]) +
  '</div>';
};

/* P-12 照片地图 */
pages.p12 = function () {
  return '<div class="page">' +
    appbar(t('p12_t'), '<button class="iconbtn" data-act="nop">' + ic('filter') + '</button>', 'p02') +
    '<div class="map">' + contours() +
      '<svg class="lines" viewBox="0 0 100 100" preserveAspectRatio="none">' + poly(TRACK, '#E24B4A', 5, null) + '</svg>' +
      CLUSTERS.map(function (c, i) {
        var sz = c.n >= 50 ? 40 : c.n >= 10 ? 34 : 28;
        return '<div class="cluster' + (i === 3 ? ' s2' : '') + '" style="left:' + c.x + '%;top:' + c.y +
          '%;width:' + sz + 'px;height:' + sz + 'px;font-size:' + (sz >= 38 ? 13 : 11) + 'px" ' +
          'data-act="opencluster" data-i="' + i + '">' + c.n + '</div>';
      }).join('') +
      '<div class="mapbtns"><button class="mapbtn" data-act="nop">' + ic('layers') + '</button>' +
      '<button class="mapbtn" data-act="nop">' + ic('locate') + '</button></div>' +
      '<div style="position:absolute;left:12px;bottom:16px;font-size:10px;color:#5A616B;' +
        'background:rgba(255,255,255,.86);padding:4px 8px;border-radius:6px">' +
        (L() === 'en' ? 'WGS-84 → GCJ-02 converted' : '已由 WGS-84 转为 GCJ-02') + '</div>' +
    '</div>' +
  '</div>';
};

/* P-13 全屏媒体查看器 */
pages.p13 = function () {
  var i = S.selPhoto;
  return '<div class="viewer">' +
    '<div class="vt"><button class="iconbtn" data-go="p12">' + ic('back') + '</button>' +
      '<span>' + (i + 1) + ' / 14</span><span class="sp"></span>' +
      '<button class="iconbtn" data-act="nop">' + ic('share') + '</button></div>' +
    '<div class="vm"><div style="width:100%;aspect-ratio:3/4;max-height:100%;border-radius:8px;' +
      'background:linear-gradient(150deg,' + gradOf(i) + ')"></div></div>' +
    '<div class="vb">' +
      '<button class="iconbtn" data-act="prevph">' + ic('back') + '</button>' +
      '<button class="btn gray" data-act="nop">' + ic('map') + t('p13_map') + '</button>' +
      '<button class="iconbtn" data-act="nextph">' + ic('chevR') + '</button>' +
    '</div>' +
  '</div>';
};

/* P-14 设置 */
var SETCFG = [
  { g:'g_general', items:[
    {k:'lang', type:'sel', label:'s_lang', opts:'s_lang_v'} ]},
  { g:'g_alert', items:[
    {k:'alert', type:'sw', label:'s_alert'},
    {k:'voice', type:'sw', label:'s_voice'},
    {k:'dalert', type:'sw', label:'s_dalert'},
    {k:'dval', type:'num', label:'s_dval', min:50, max:5000, step:50, unit:'m'},
    {k:'aalert', type:'sw', label:'s_aalert'},
    {k:'aval', type:'num', label:'s_aval', min:10, max:1000, step:10, unit:'m'},
    {k:'vibe', type:'sw', label:'s_vibe'} ]},
  { g:'g_rec', items:[
    {k:'locmode', type:'sel', label:'s_locmode', opts:'s_locmode_v'},
    {k:'autopause', type:'sw', label:'s_autopause'},
    {k:'screenon', type:'sw', label:'s_screenon'},
    {k:'bg', type:'sw', label:'s_bg'} ]},
  { g:'g_unit', items:[
    {k:'udist', type:'sel', label:'s_udist', opts:'s_udist_v'},
    {k:'ualt', type:'sel', label:'s_ualt', opts:'s_ualt_v'},
    {k:'pace', type:'sel', label:'s_pace', opts:'s_pace_v'} ]},
  { g:'g_map', items:[
    {k:'maptype', type:'sel', label:'s_maptype', opts:'s_maptype_v'},
    {k:'lw', type:'sel', label:'s_lw', opts:'s_lw_v'},
    {k:'photocl', type:'sw', label:'s_photocl'} ]},
  { g:'g_data', items:[
    {k:'crs', type:'sel', label:'s_crs', opts:'s_crs_v'},
    {k:'gpx', type:'sel', label:'s_gpx', opts:'s_gpx_v'},
    {k:'media', type:'sw', label:'s_media'},
    {k:'cksum', type:'sw', label:'s_cksum'},
    {k:'conflict', type:'sel', label:'s_conflict', opts:'s_conflict_v'},
    {k:'expsingle', type:'btn', label:'s_expsingle', go:'p16'},
    {k:'expzip', type:'btn', label:'s_expzip', go:'p16'},
    {k:'impbtn', type:'btn', label:'s_impbtn', go:'p17'} ]},
  { g:'g_about', items:[ {k:'about', type:'btn', label:'p14_about', go:'p15'} ]}
];
pages.p14 = function () {
  var html = '';
  SETCFG.forEach(function (grp) {
    html += '<div class="sec-title">' + t(grp.g) + '</div><div class="card">';
    grp.items.forEach(function (it) {
      html += '<div class="rowline"><div class="lb"><div class="t">' + t(it.label) + '</div>';
      if (it.k === 'aval') html += '<div class="s">' + t('s_alert_note') + '</div>';
      html += '</div>';
      if (it.type === 'sw') {
        html += '<div class="sw' + (S.settings[it.k] ? ' on' : '') + '" data-act="sw" data-k="' + it.k +
                '"><i></i></div>';
      } else if (it.type === 'sel') {
        var opts = t(it.opts), v = S.settings[it.k];
        var idx = (it.k === 'lang') ? (S.settings.lang === 'en' ? 2 : 0) : ['high','std','km','m','pace','thin','GCJ-02','gpx','skip'].indexOf(v);
        if (it.k === 'locmode') idx = v === 'high' ? 0 : 1;
        if (it.k === 'udist') idx = v === 'km' ? 0 : 1;
        if (it.k === 'ualt') idx = v === 'm' ? 0 : 1;
        if (it.k === 'pace') idx = v === 'pace' ? 0 : 1;
        if (it.k === 'maptype') idx = v === 'std' ? 0 : 1;
        if (it.k === 'lw') idx = ['thin','mid','thick'].indexOf(v);
        if (it.k === 'crs') idx = v === 'GCJ-02' ? 0 : 1;
        if (it.k === 'gpx') idx = v === 'gpx' ? 0 : 1;
        if (it.k === 'conflict') idx = ['skip','overwrite','copy','ask'].indexOf(v);
        if (idx < 0) idx = 0;
        html += '<div class="vl" data-act="nop" style="cursor:pointer">' + opts[idx] + ' ' +
          ic('chevR', 'chev') + '</div>';
      } else if (it.type === 'num') {
        html += '<div class="vl" data-act="nop" style="cursor:pointer"><span class="tag n">' +
          S.settings[it.k] + ' ' + t(it.unit) + '</span></div>';
      } else if (it.type === 'btn') {
        html += '<button class="btn gray sm" data-go="' + it.go + '">' + t(it.label) + '</button>';
      }
      html += '</div>';
    });
    html += '</div>';
  });
  return '<div class="page">' + appbar(t('p14_t')) + '<div style="padding-bottom:8px">' + html + '</div>' +
    '<div class="card" style="margin:0 12px 14px"><div class="rowline">' +
      '<div class="lb"><div class="t">' + t('s_lang') + '</div></div>' +
      '<div class="chips">' +
        ['zh','en'].map(function (g) {
          return '<button class="chip' + (S.lang === g ? ' on' : '') + '" data-act="lang" data-v="' + g + '">' +
            (g === 'zh' ? '简体中文' : 'English') + '</button>';
        }).join('') + '</div></div></div>' + '</div>';
};

/* P-15 关于 */
pages.p15 = function () {
  return '<div class="page">' + appbar(t('p15_t'), '', 'p14') +
    '<div class="pad" style="text-align:center;padding-top:26px">' +
      '<div style="width:60px;height:60px;margin:0 auto 12px;color:#2F80ED">' + ic('mtn') + '</div>' +
      '<div style="font-size:19px;font-weight:700">' + t('app') + '</div>' +
      '<div style="font-size:12px;color:var(--text-2);margin-top:4px">v1.0.0 (1)</div>' +
      '<div style="font-size:13px;color:var(--text-2);margin-top:14px;line-height:1.7">' + t('p15_desc') + '</div>' +
    '</div>' +
    '<div class="card"><div class="rowline"><div class="lb"><div class="t">' + t('p15_repo') + '</div>' +
      '<div class="s">github.com/hxzhang2000/GoHiking</div></div>' + ic('chevR', 'chev') + '</div>' +
      '<div class="rowline"><div class="lb"><div class="t">' + t('p15_lic') + '</div></div>' +
      '<div class="vl">Apache-2.0</div></div>' +
      '<div class="rowline"><div class="lb"><div class="t">' + t('p15_priv') + '</div></div>' +
      ic('chevR', 'chev') + '</div>' +
      '<div class="rowline"><div class="lb"><div class="t">' + t('p15_issue') + '</div></div>' +
      ic('chevR', 'chev') + '</div></div>' +
    '<div class="hint">' + (L() === 'en'
      ? 'This build uses no real AMap key. Configure yours in local.properties before building.'
      : '本原型不含真实高德 Key。构建前请在 local.properties 中配置你自己的 Key。') + '</div>' +
  '</div>';
};

/* P-16 导出进度 */
pages.p16 = function () {
  var e = S.exp;
  var fname = 'GoHiking_Backup_20260919_165800.zip';
  return '<div class="page">' + appbar(t('p16_t'), '', 'p14') +
    '<div class="pad">' +
      '<div class="card" style="margin:0">' +
        '<div class="rowline"><div class="lb"><div class="t">' + t('p16_file') + '</div>' +
          '<div class="s">' + fname + '</div></div>' +
          '<span class="tag ' + (e.done ? 'g' : 'b') + '">' + (e.done ? t('p16_done') : t('p16_prep')) + '</span></div>' +
        '<div style="padding:4px 14px 16px">' +
          '<div class="prog' + (e.done ? ' ok' : '') + '"><i style="width:' + e.pct + '%"></i></div>' +
          '<div style="font-size:12px;color:var(--text-2);margin-top:8px">' + e.pct + '% · ' +
            (e.done ? '42 ' + t('p17_cnt_t') + ' · 5 ' + t('p17_cnt_r')
              : Math.round(e.pct / 100 * 42) + ' / 42 ' + t('p17_cnt_t')) + '</div>' +
        '</div>' +
      '</div>' +
      (e.done ? '<div class="hint" style="padding-top:14px">' + (L() === 'en'
        ? 'Exported to the folder you picked. The ZIP contains one JSON per trip plus manifest.json.'
        : '已导出到你选择的目录。ZIP 内含逐条记录的 JSON 与 manifest.json。') + '</div>' : '') +
    '</div>' +
    acts(e.done
      ? [{ label:t('done'), go:'p14' },
         { label:t('share'), act:'nop', cls:'pri', icon:'share' }]
      : [{ label:t('cancel'), act:'cancelxp' }]) +
  '</div>';
};

/* P-17 导入预览 */
pages.p17 = function () {
  var pol = S.imp.policy;
  return '<div class="page">' + appbar(t('p17_t'), '', 'p14') +
    '<div class="pad">' +
      '<div class="card" style="margin:0">' +
        '<div class="rowline"><div class="lb"><div class="t">' + t('p17_file') + '</div>' +
          '<div class="s">GoHiking_Backup_20260919_165800.zip · 4.2 MB</div></div>' +
          '<span class="tag b">ZIP</span></div>' +
        '<div class="rowline"><div class="lb"><div class="t">' + t('p17_cnt_t') + '</div></div>' +
          '<div class="vl">42</div></div>' +
        '<div class="rowline"><div class="lb"><div class="t">' + t('p17_cnt_r') + '</div></div>' +
          '<div class="vl">5</div></div>' +
        '<div class="rowline"><div class="lb"><div class="t">' + t('p17_cnt_m') + '</div></div>' +
          '<div class="vl">0</div></div>' +
        '<div class="rowline"><div class="lb"><div class="t">' + t('p17_range') + '</div></div>' +
          '<div class="vl">2026-03-02 — 2026-09-19</div></div>' +
        '<div class="rowline"><div class="lb"><div class="t">' + t('p17_conflict') + '</div></div>' +
          '<div class="vl"><span class="tag o">3 ' + t('p17_conflict') + '</span></div></div>' +
      '</div>' +
      '<div class="card" style="margin-top:12px;background:#FDF6E7">' +
        '<div class="rowline"><span style="color:#B7791F;flex:none">' + ic('warn') + '</span>' +
        '<div class="lb"><div class="s" style="color:#7A5310">' + t('p17_crs_warn') + '</div></div></div>' +
      '</div>' +
      '<div class="sec-title" style="padding-left:2px">' + t('p17_policy') + '</div>' +
      '<div class="chips" style="padding:0 2px">' +
        [['skip','p18_pol'],['overwrite','p18_pol2'],['copy','p18_pol3'],['ask','p18_pol4']].map(function (c) {
          return '<button class="chip' + (pol === c[0] ? ' on' : '') + '" data-act="policy" data-v="' + c[0] + '">' +
            t(c[1]) + '</button>';
        }).join('') + '</div>' +
      '<div class="sec-title" style="padding-left:2px">' + t('p17_conflict') + ' · ' + t('p17_cnt_t') + '</div>' +
      '<div class="card" style="margin:0">' +
        [['梧桐山','Wutongshan','2026-09-19 07:12'],
         ['梧桐山','Wutongshan','2026-09-19 07:12'],
         ['七娘山','Qiniangshan','2026-08-11 06:40']].map(function (c, i) {
          return '<div class="rowline"><div class="lb"><div class="t">' + esc(L() === 'en' ? c[1] : c[0]) +
            '</div><div class="s">' + c[2] + ' · ' +
            (i === 2 ? (L() === 'en' ? 'Same name + start time — possible duplicate' : '名称 + 开始时间相同 — 疑似重复')
                     : (L() === 'en' ? 'Same trip id' : 'trip.id 相同')) + '</div></div>' +
            '<span class="tag ' + (i === 2 ? 'n' : 'o') + '">' +
            (i === 2 ? (L() === 'en' ? 'Suspected' : '疑似') : t('p17_conflict')) + '</span></div>';
        }).join('') + '</div>' +
    '</div>' +
    acts([{ label:t('p17_onlyview'), go:'p14' },
           { label:t('p17_start'), go:'p18', cls:'pri' }]) +
  '</div>';
};

/* P-18 导入结果 */
pages.p18 = function () {
  return '<div class="page">' + appbar(t('p18_t'), '', 'p14') +
    '<div class="pad">' +
      '<div class="card" style="margin:0"><div class="summary">' +
        '<div class="c"><div class="v" style="color:var(--ok)">38</div><div class="k">' + t('p18_ok') + '</div></div>' +
        '<div class="c"><div class="v" style="color:var(--text-2)">3</div><div class="k">' + t('p18_skip') + '</div></div>' +
        '<div class="c"><div class="v" style="color:var(--danger)">1</div><div class="k">' + t('p18_fail') + '</div></div>' +
      '</div></div>' +
      '<div class="sec-title">' + t('p18_reason') + '</div>' +
      '<div class="card" style="margin:0">' +
        '<div class="rowline"><div class="lb"><div class="t">trips/8f21c0a4-....json</div>' +
          '<div class="s">' + (L() === 'en'
            ? 'Checksum mismatch — file skipped (see 7.5)'
            : '校验和不匹配 — 已跳过该文件（见 7.5）') + '</div></div>' +
          '<span class="tag r">' + t('p18_fail') + '</span></div>' +
      '</div>' +
      '<div class="hint" style="padding-top:14px">' + (L() === 'en'
        ? 'Import is transactional: one failed file never affects the others, and re-importing the same archive produces no duplicates.'
        : '导入是事务化的：单条失败不影响其他条；重复导入同一备份不会产生重复数据。') + '</div>' +
    '</div>' +
    acts([{ label:t('done'), go:'p14', cls:'pri' }]) +
  '</div>';
};

function gradOf(i) {
  var a = ['#7BA05B,#3D5A2A', '#5B84A0,#26404F', '#A08A5B,#4F4026',
           '#8A5B7B,#3F2540', '#5BA08C,#264F45', '#A05B5B,#4F2626'];
  return a[i % a.length];
}
function photoStrip(active) {
  var out = '';
  for (var i = 0; i < 14; i++) {
    out += '<div class="ph' + (i === active ? ' on' : '') + '" data-act="openph" data-i="' + i + '" ' +
      'style="background:linear-gradient(150deg,' + gradOf(i) + ')">' +
      (i % 4 === 3 ? '<span class="vid">0:' + String(12 + i).padStart(2, '0') + '</span>' : '') + '</div>';
  }
  return out;
}

/* ---------------- 渲染 ---------------- */
var view = document.getElementById('view');
var overlay = document.getElementById('overlay');

function render() {
  var fn = pages[S.page] || pages.p02;
  var html = fn();
  if (S.page === 'p13') { view.innerHTML = ''; overlay.innerHTML = html; }
  else { overlay.innerHTML = ''; view.innerHTML = html; }
  var tb = document.getElementById('tabbar');
  if (['p02','p07','p10','p14'].indexOf(S.page) >= 0) {
    tb.style.display = 'flex'; tb.innerHTML = tabbar();
  } else { tb.style.display = 'none'; tb.innerHTML = ''; }
  renderSheet();
  document.documentElement.lang = S.lang === 'en' ? 'en' : 'zh-CN';
  syncSide();
  var q = document.getElementById('q');
  if (q && S.plan.q && document.activeElement !== q) { q.focus(); }
}
function renderSheet() {
  var sh = document.getElementById('sheet');
  if (S.sheetCluster == null) { sh.innerHTML = ''; return; }
  var c = CLUSTERS[S.sheetCluster];
  sh.innerHTML =
    '<div class="mask" data-act="closesheet"></div>' +
    '<div class="sheet"><div class="grab"></div>' +
      '<div class="sh"><span>' + c.n + ' ' + t('p12_sheet') + '</span>' +
        '<button class="iconbtn" data-act="closesheet">' + ic('close') + '</button></div>' +
      '<div class="sc"><div class="photostrip">' + photoStrip(S.selPhoto) + '</div>' +
      '<div class="hint">' + (L() === 'en'
        ? 'Swipe to browse; the map follows the selected photo.'
        : '左右滑动浏览，上方地图自动定位到对应点。') + '</div>' +
      '<div style="height:14px"></div></div>' +
    '</div>';
}
function syncSide() {
  var els = document.querySelectorAll('.side .pbtn');
  for (var i = 0; i < els.length; i++) {
    els[i].classList.toggle('on', els[i].getAttribute('data-go') === S.page);
  }
}

/* ---------------- 记录模拟 ---------------- */
var timer = null;
function startTimer() {
  stopTimer();
  timer = setInterval(function () {
    var r = S.rec;
    if (!r.active || r.paused) return;
    r.sec += 1;
    r.dist += 1.4;
    r.steps += 2;
    var prevAlt = r.alt;
    var progAlt = Math.min(1, r.sec / 2400);   // 前 60% 上坡、后 40% 下撤，覆盖 ALERT_DESCENT
    r.alt = progAlt < 0.6
      ? 120 + (940 - 120) * (progAlt / 0.6)
      : 940 - (940 - 140) * ((progAlt - 0.6) / 0.4);
    r.alt += Math.sin(r.sec / 7) * 3;
    var dAlt = r.alt - prevAlt;
    if (dAlt >= 0) { r.asc += dAlt; } else { r.desc += -dAlt; }
    if (S.settings.alert) {
      if (S.settings.dalert && Math.floor(r.dist / S.settings.dval) > r.lastD) {
        r.lastD = Math.floor(r.dist / S.settings.dval);
        addMarker('ALERT_DISTANCE', (r.lastD * S.settings.dval) >= 1000
          ? (r.lastD * S.settings.dval / 1000).toFixed(0) + 'km'
          : (r.lastD * S.settings.dval) + 'm');
        toast((S.settings.voice ? '[TTS] ' : '') + (L() === 'en'
          ? 'Distance ' + (r.lastD * S.settings.dval) + ' m' : '已行进 ' + (r.lastD * S.settings.dval) + ' 米'));
      }
      if (S.settings.aalert && Math.floor(r.asc / S.settings.aval) > r.lastA) {
        r.lastA = Math.floor(r.asc / S.settings.aval);
        addMarker('ALERT_ASCENT', '+' + (r.lastA * S.settings.aval) + 'm');
        toast((S.settings.voice ? '[TTS] ' : '') + (L() === 'en'
          ? 'Climbed ' + (r.lastA * S.settings.aval) + ' m, altitude ' + Math.round(r.alt) + ' m'
          : '已爬升 ' + (r.lastA * S.settings.aval) + ' 米，当前海拔 ' + Math.round(r.alt) + ' 米'));
      }
      if (S.settings.aalert && Math.floor(r.desc / S.settings.aval) > r.lastDE) {
        r.lastDE = Math.floor(r.desc / S.settings.aval);
        addMarker('ALERT_DESCENT', '-' + (r.lastDE * S.settings.aval) + 'm');
        toast((S.settings.voice ? '[TTS] ' : '') + (L() === 'en'
          ? 'Descended ' + (r.lastDE * S.settings.aval) + ' m, altitude ' + Math.round(r.alt) + ' m'
          : '已下降 ' + (r.lastDE * S.settings.aval) + ' 米，当前海拔 ' + Math.round(r.alt) + ' 米'));
      }
    }
    if (S.page === 'p08') {
      var set = function (id, v) { var e = document.getElementById(id); if (e) e.innerHTML = v; };
      set('rDist', (r.dist / 1000).toFixed(2));
      set('rTime', fmtDur(r.sec));
      set('rAlt', fmtAltN(r.alt) + '<span style="font-size:11px;color:var(--text-2)"> ' + altUnit() + '</span>');
      set('rAsc', '+' + fmtAltN(r.asc));
      set('rSteps', r.steps);
      var m = document.querySelector('.map .lines');
      if (m) {
        var prog = Math.min(1, r.sec / 2400);
        var cut = Math.max(2, Math.round(TRACK.length * (0.15 + prog * 0.85)));
        m.innerHTML = poly(TRACK.slice(0, 11), '#2F80ED', 6, null) + poly(TRACK.slice(0, cut), '#E24B4A', 7, null);
      }
    }
  }, 1000);
}
function stopTimer() { if (timer) { clearInterval(timer); timer = null; } }
function addMarker(type, label) {
  var r = S.rec;
  var prog = Math.min(1, r.sec / 2400);
  var cut = Math.max(2, Math.round(TRACK.length * (0.15 + prog * 0.85)));
  var p = TRACK[Math.min(TRACK.length - 1, cut - 1)];
  var n = r.markers.filter(function (m) { return m.t === type; }).length + 1;
  r.markers.push({ t:type, x:p[0], y:p[1], n:n, label:label, alt:r.alt });
  if (S.page === 'p08') render();
}

/* ---------------- 事件 ---------------- */
function go(p) {
  if (p === 'p08' && !S.rec.active) { /* 直接进入时保持状态 */ }
  S.page = p;
  if (p !== 'p12') S.sheetCluster = null;
  if (p === 'p13') { /* fullscreen */ }
  window.scrollTo(0, 0);
  render();
  if (p === 'p16' && !S.exp.done && !S.exp.running) runExport();
}

document.addEventListener('click', function (ev) {
  var el = ev.target.closest('[data-go],[data-act]');
  if (!el) return;
  var go2 = el.getAttribute('data-go');
  if (go2) { go(go2); return; }
  var act = el.getAttribute('data-act');
  handle(act, el, ev);
});

function handle(act, el, ev) {
  var v = el.getAttribute('data-v');
  var k = el.getAttribute('data-k');
  var i = el.getAttribute('data-i');

  switch (act) {
    case 'nop':
      toast(L() === 'en' ? 'Demo: not wired in this prototype' : '演示：原型中未接线');
      break;

    /* 权限 */
    case 'perm':
      S.perms[k] = true; render(); break;
    case 'permall':
      S.perms = { loc:true, notif:true, sensor:true, media:true }; go('p02'); break;

    /* 选点 */
    case 'pickmode':
      S.plan.picking = v; render(); break;
    case 'useloc':
      S.plan[S.plan.picking] = { n: L() === 'en' ? 'My location' : '我的位置', x:6, y:90 };
      toast(L() === 'en' ? 'Set to current location' : '已设为当前位置'); render(); break;
    case 'swap':
      var a = S.plan.start, b = S.plan.end; S.plan.start = b; S.plan.end = a; render(); break;
    case 'poi':
      var nx = parseFloat(el.getAttribute('data-x')), ny = parseFloat(el.getAttribute('data-y'));
      var nn = el.getAttribute('data-n');
      S.plan[S.plan.picking] = { n:nn, x:nx, y:ny };
      toast((L() === 'en' ? 'Picked map label: ' : '已点选地图标记：') + nn);
      if (S.plan.picking === 'start') S.plan.picking = 'end';
      render(); break;
    case 'pickpoi':
      var sx = parseFloat(el.getAttribute('data-x')), sy = parseFloat(el.getAttribute('data-y'));
      S.plan[S.plan.picking] = { n:el.getAttribute('data-n'), x:sx, y:sy };
      S.plan.q = '';
      if (S.plan.picking === 'start') S.plan.picking = 'end';
      render(); break;
    case 'torec':
      if (!S.plan.start || !S.plan.end) { toast(t('p03_need')); return; }
      go('p04'); break;
    case 'tomanual':
      S.plan.wps = []; go('p05'); break;
    case 'selcand':
      S.plan.sel = parseInt(i, 10); render(); break;

    /* 手动打点 */
    case 'addwp': {
      var box = el.getBoundingClientRect();
      var x = (ev.clientX - box.left) / box.width * 100;
      var y = (ev.clientY - box.top) / box.height * 100;
      if (S.plan.wps.length >= 50) { toast(t('p05_limit')); return; }
      S.plan.wps.push({ x:x, y:y }); render(); break;
    }
    case 'undowp': S.plan.wps.pop(); render(); break;
    case 'snap': S.plan.snap = v; render(); break;
    case 'finishwp':
      if (S.plan.wps.length < 2) { toast(t('p05_need')); return; }
      S.plan.start = { n:'WP1', x:S.plan.wps[0].x, y:S.plan.wps[0].y };
      S.plan.end = { n:'WP' + S.plan.wps.length,
        x:S.plan.wps[S.plan.wps.length - 1].x, y:S.plan.wps[S.plan.wps.length - 1].y };
      go('p06'); break;

    /* 保存计划 */
    case 'saveroute': {
      var ni = document.getElementById('pname'), no = document.getElementById('pnote');
      var nmv = (ni && ni.value) || '梧桐山';
      S.routes.unshift({ id:'pr-' + Date.now(), name:nmv, nameEn:nmv,
        dist:8456, asc:650, desc:630, min:146, diff:'MODERATE',
        created:'2026-09-19', legs:true, note: no ? no.value : '' });
      S.plan = { start:null, end:null, picking:'start', q:'', mode:'auto', sel:0, wps:[],
                 snap:'straight', name:'', note:'' };
      toast(L() === 'en' ? 'Planned route saved' : '计划线路已保存');
      go('p07'); break;
    }
    case 'openroute':
      toast(L() === 'en' ? 'Demo: shown as blue line on the map' : '演示：已在地图上以蓝线显示');
      go('p02'); break;

    /* 记录 */
    case 'startrec':
      S.rec = { active:true, paused:false, sec:0, dist:0, asc:0, desc:0, steps:0, alt:120,
                markers:[], name:'梧桐山', note:'', lastD:0, lastA:0, lastDE:0 };
      startTimer(); go('p08'); break;
    case 'pause':
      S.rec.paused = !S.rec.paused; render(); break;
    case 'mark':
      addMarker('MANUAL', L() === 'en' ? 'Water' : '补水点');
      toast(L() === 'en' ? 'Manual marker added' : '已添加手动标记'); break;
    case 'summit':
      addMarker('SUMMIT', fmtAltN(S.rec.alt) + altUnit());
      toast(L() === 'en' ? 'Summit marked' : '已标记登顶点'); break;
    case 'togglealert':
      S.settings.alert = !S.settings.alert; render(); break;
    case 'backrec':
      toast(L() === 'en' ? 'Recording continues in the background' : '记录仍在后台继续');
      go('p02'); break;
    case 'stoprec':
      stopTimer(); S.rec.active = false; go('p09'); break;
    case 'savetrip': {
      var r = S.rec;
      var ni2 = document.querySelector('[data-act="rname"]');
      var no2 = document.querySelector('[data-act="rnote"]');
      S.trips.unshift({
        id:'t' + Date.now(), name:(ni2 && ni2.value) || r.name,
        nameEn:(ni2 && ni2.value) || 'Hike',
        date:'2026-09-19 ' + new Date().toTimeString().slice(0, 5),
        dur:r.sec, moving:r.sec, paused:0, dist:r.dist, asc:r.asc, desc:r.desc,
        max:r.alt, min:120, steps:r.steps, kcal:Math.round(r.steps * 0.04),
        altSrc:'GPS_ONLY', stepSrc:'SENSOR_COUNTER',
        out:{d:r.dist * 0.52, a:r.asc, de:0, t:r.sec * 0.52},
        ret:{d:r.dist * 0.48, a:0, de:r.desc, t:r.sec * 0.48},
        markers:r.markers.map(function (m) {
          return { t:m.t, n:m.n, ts:fmtDur(r.sec).slice(0, 5),
                   alt:m.alt, extra:m.label || '', note:m.t === 'MANUAL' ? (L() === 'en' ? 'Water' : '补水点') : '' };
        })
      });
      S.rec.active = false; stopTimer();
      toast(L() === 'en' ? 'Trip saved' : '记录已保存');
      go('p10'); break;
    }
    case 'discard':
      overlay.insertAdjacentHTML('beforeend', dialog(t('p09_discard'), '<div>' + t('p09_discard_q') + '</div>',
        [{ label:t('cancel'), act:'closedlg' },
         { label:t('p09_discard'), act:'dodiscard', cls:'dgr' }]));
      break;
    case 'dodiscard':
      S.rec = { active:false, paused:false, sec:0, dist:0, asc:0, desc:0, steps:0, alt:120,
                markers:[], name:'梧桐山', note:'', lastD:0, lastA:0, lastDE:0 };
      stopTimer(); closeDlg(); go('p02'); break;
    case 'closedlg': closeDlg(); break;

    /* 记录详情 */
    case 'opentrip':
      S.cur = S.trips[parseInt(i, 10)]; S.tab11 = 'ov'; go('p11'); break;
    case 'tab11': S.tab11 = v; render(); break;

    /* 照片 */
    case 'opencluster': S.sheetCluster = parseInt(i, 10); renderSheet(); break;
    case 'closesheet': S.sheetCluster = null; renderSheet(); break;
    case 'openph': S.selPhoto = parseInt(i, 10); go('p13'); break;
    case 'prevph': S.selPhoto = (S.selPhoto + 13) % 14; render(); break;
    case 'nextph': S.selPhoto = (S.selPhoto + 1) % 14; render(); break;

    /* 设置 */
    case 'sw': S.settings[k] = !S.settings[k]; render(); break;
    case 'lang':
      S.settings.lang = v; S.lang = v; render();
      toast(v === 'en' ? 'Language switched to English' : '已切换为简体中文'); break;
    case 'policy': S.imp.policy = v; render(); break;

    /* 导出 */
    case 'cancelxp':
      overlay.insertAdjacentHTML('beforeend', dialog(t('p16_t'), '<div>' + t('p16_cancel_q') + '</div>',
        [{ label:t('cancel'), act:'closedlg' }, { label:L() === 'en' ? 'Cancel export' : '取消导出',
          act:'docalcelxp', cls:'dgr' }]));
      break;
    case 'docalcelxp':
      S.exp = { running:false, pct:0, done:false }; closeDlg(); go('p14'); break;

    /* 演示控制台 */
    case 'emptytrips':
      S.trips = []; S.cur = null;
      toast(L() === 'en' ? 'Trips cleared — empty state' : '已清空记录，展示空状态'); go('p10'); break;
    case 'emptyroutes':
      S.routes = []; toast(L() === 'en' ? 'Routes cleared — empty state' : '已清空计划，展示空状态');
      go('p07'); break;
    case 'resetdemo':
      S.trips = [SAMPLE]; S.cur = null;
      S.routes = [{ id:'pr-001', name:'梧桐山 泰山涧→好汉坡', nameEn:'Wutongshan, Taishanjian → HaoHanPo',
        dist:8456, asc:650, desc:630, min:146, diff:'MODERATE', created:'2026-09-18', legs:true }];
      S.rec = { active:false, paused:false, sec:0, dist:0, asc:0, desc:0, steps:0, alt:120,
                markers:[], name:'梧桐山', note:'', lastD:0, lastA:0, lastDE:0 };
      S.perms = { loc:false, notif:false, sensor:false, media:false };
      stopTimer();
      toast(L() === 'en' ? 'Demo data restored' : '演示数据已重置'); go('p01'); break;
  }
}
function closeDlg() {
  var d = overlay.querySelector('.dlg'); if (d) d.remove();
}

/* 导出动画 */
var expTimer = null;
function runExport() {
  S.exp = { running:true, pct:0, done:false };
  clearInterval(expTimer);
  expTimer = setInterval(function () {
    S.exp.pct += Math.random() * 13 + 5;
    if (S.exp.pct >= 100) {
      S.exp.pct = 100; S.exp.done = true; S.exp.running = false;
      clearInterval(expTimer);
    }
    if (S.page === 'p16') render();
  }, 260);
}

document.addEventListener('input', function (ev) {
  var el = ev.target;
  var act = el.getAttribute && el.getAttribute('data-act');
  if (act === 'q') { S.plan.q = el.value; render();
    var q = document.getElementById('q');
    if (q) { q.focus(); q.setSelectionRange(q.value.length, q.value.length); } }
});
/* 直接跳页时重置导出状态 */
var _go = go;
go = function (p) { if (p !== 'p16') { clearInterval(expTimer); } _go(p); };

/* ---------------- 侧栏 ---------------- */
var PAGES = [
  ['p01','P-01','权限引导页'], ['p02','P-02','首页地图'], ['p03','P-03','计划 · 选点页'],
  ['p04','P-04','计划 · 推荐列表'], ['p05','P-05','计划 · 手动打点'], ['p06','P-06','计划 · 保存'],
  ['p07','P-07','计划线路列表'], ['p08','P-08','记录页'], ['p09','P-09','记录保存页'],
  ['p10','P-10','运动记录列表'], ['p11','P-11','运动记录详情'], ['p12','P-12','照片地图'],
  ['p13','P-13','全屏媒体查看器'], ['p14','P-14','设置'], ['p15','P-15','关于'],
  ['p16','P-16','导出进度'], ['p17','P-17','导入预览'], ['p18','P-18','导入结果']
];
function buildSide() {
  var box = document.getElementById('plist');
  box.innerHTML = PAGES.map(function (p) {
    return '<button class="pbtn" data-go="' + p[0] + '"><code>' + p[1] + '</code>' + p[2] + '</button>';
  }).join('');
}

/* ---------------- 启动 ---------------- */
/* 支持 #p11 这样的深链直达页面（刷新后仍停留在当前页） */
var _hash = (location.hash || '').replace('#', '');
if (pages[_hash]) S.page = _hash;
var _goHash = go;
go = function (p) { try { history.replaceState(null, '', '#' + p); } catch (e) {} _goHash(p); };

function fit() {
  var ph = document.querySelector('.phone');
  var h = window.innerHeight - 40, w = window.innerWidth - 380;
  var s = Math.min(1, h / 874, w / 415);
  if (s <= 0) s = 1;
  ph.style.transform = 'scale(' + s.toFixed(3) + ')';
  var st = document.querySelector('.stage');
  st.style.height = (874 * s) + 'px';
}
window.addEventListener('resize', fit);
buildSide();
fit();
render();

/* 启动时钟 */
setInterval(function () {
  var e = document.getElementById('clock');
  if (e) { var d = new Date();
    e.textContent = d.getHours() + ':' + String(d.getMinutes()).padStart(2, '0'); }
}, 10000);

})();
