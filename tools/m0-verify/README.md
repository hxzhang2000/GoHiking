# M0 前置验证脚本（PC 端，无需真机）

这里放的是 `docs/DEV-DESIGN.md` §9.2「动工前必须清空的实测与查证清单」中**能在 PC 上先做掉的那部分**的验证脚本。
目的：把「凭经验写死」的参数和「听说过但没查」的 API，在 M0 建骨架之前就用可复跑的方式落实。

> 结论汇总见 `docs/DEV-DESIGN.md` §9.2.1；未闭环项见 §9.2.2 / §9.2.3。

## 怎么跑

```powershell
# 用托管 Python（本机 PATH 里没有 python）
$py = "C:\Users\hxzha\.workbuddy\binaries\python\versions\3.13.12\python.exe"
& $py tools/m0-verify/01_maven_versions.py      2>&1 | Out-File -Encoding utf8 .tmp/01.log
& $py tools/m0-verify/11_gcj02_numbers.py       2>&1 | Out-File -Encoding utf8 .tmp/11.log
```

脚本都是**只读**的：只发 HTTP 请求、只解包已下载的 jar，不改仓库里的任何东西。
`04` / `06` / `08` / `10` 会把 jar 下到 `dl/`（约 40 MB，已在 `.gitignore` 中排除）。

## 脚本清单

| 脚本 | 回答什么问题 | 对应待办 |
| --- | --- | --- |
| `01_maven_versions.py` | §1.3 里那套「凭经验填的」版本号，到底存不存在？各仓库最新是什么？ | B1、T-01、T-10、T-11、T-14 |
| `04_amap_jar_inspect.py` | 高德 SDK 包里有没有 `ClusterOverlay`？`MapsInitializer` 的隐私接口签名是什么？有没有内置混淆规则？ | T-05、T-06、T-16、T-17、T-02 |
| `06_locate_search_classes.py` | `PoiSearch` / `Inputtips` / 路径规划类到底在哪个 artifact、哪个包？ | PRD 6.2.1 |
| `08_key_checks.py` | `WALK_MULTI_PATH` 存不存在？`WalkRouteQuery` 构造要不要传 mode？`TravelStrategy` 有没有 `MULTIPLE`？定位模式常量怎么拼？ | T-13、T-15①②、T-02 |
| `10_routesearchv2.py` | V2 版算路 API 的 `AlternativeRoute` 是什么？`PoiSearch` / `Inputtips` 的公开方法签名？ | T-15（多路径首选方案）、PRD 6.2.1 |
| `11_gcj02_numbers.py` | GCJ-02 相对 WGS-84 的偏移量真实区间？用 GCJ-02 算距离误差多大？迭代几次收敛？ | B2、B3、B4、（T-09 算法侧） |
| `12_elevation_services.py` | 高程服务在国内网络下通不通？批量能力？DEM 精度够不够？ | T-07 |
| `jlib.py` | 共享依赖：极简 Java class 文件 / jar 解析器（不依赖 JDK，纯 Python） | — |

## 不能靠 PC 代替的（别在这上面浪费时间）

- 传感器：气压计、计步器、加速度计（T-08、B6、B7）
- 地图渲染类：`setMapLanguage` 是否**真的**把地名变英文（T-02）、点 POI 时回调顺序（T-03）、山区 POI 覆盖度（T-04）
- 需要高德 Key 才能实跑的：`alternative_route=3` 到底返回几条（T-15③）、多路径方案差异度
- 需要真机基准的：坐标转换权威基准点（T-09）、`getMslAltitudeMeters()` 机型覆盖率（T-12）
- 需要 M0 构建产物的：`en-XA` 伪本地化（T-18）、Release 混淆后实跑（T-17 的运行期部分）
