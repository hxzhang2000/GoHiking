# -*- coding: utf-8 -*-
import sys, pathlib
sys.path.insert(0, r"C:/Users/hxzha/.workbuddy/skills/maven-artifact-api-verify/scripts")
from jlib import Jar, dump_methods

jar_path = r"C:/Users/hxzha/.gradle/caches/modules-2/files-2.1/com.amap.api/3dmap-location-search/10.0.700_loc6.4.5_sea9.7.2/4adf276861152e4e8144bcf060048118d2da384a/3dmap-location-search-10.0.700_loc6.4.5_sea9.7.2.jar"
j = Jar(pathlib.Path(jar_path).read_bytes())

path, c = j.one(r"PolylineOptions\.class$")
dump_methods(c, "PolylineOptions", want=["Custom", "Arrow", "arrow", "custom", "Dotted", "dotted", "zIndex", "texture"])

print("=== grep constants ===")
for hit in j.grep("ustomTexture"):
    print(repr(hit))
