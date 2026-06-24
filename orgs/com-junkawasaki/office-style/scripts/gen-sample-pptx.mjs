// 最小の .pptx を生成 (office-style が読む part のみを含む)。
// 実 PowerPoint としては不完全だが、決定論抽出のデモ/テストには十分。
import { zipSync, strToU8 } from "fflate";
import { writeFileSync, mkdirSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const out = resolve(here, "../examples/sample.pptx");

const theme1 = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<a:theme xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" name="GFTD Brand">
 <a:themeElements>
  <a:clrScheme name="GFTD">
   <a:dk1><a:sysClr val="windowText" lastClr="1A1A1A"/></a:dk1>
   <a:lt1><a:sysClr val="window" lastClr="FFFFFF"/></a:lt1>
   <a:dk2><a:srgbClr val="2B3A55"/></a:dk2>
   <a:lt2><a:srgbClr val="F2F4F7"/></a:lt2>
   <a:accent1><a:srgbClr val="3B82F6"/></a:accent1>
   <a:accent2><a:srgbClr val="10B981"/></a:accent2>
   <a:accent3><a:srgbClr val="F59E0B"/></a:accent3>
   <a:accent4><a:srgbClr val="EF4444"/></a:accent4>
   <a:accent5><a:srgbClr val="8B5CF6"/></a:accent5>
   <a:accent6><a:srgbClr val="14B8A6"/></a:accent6>
   <a:hlink><a:srgbClr val="2563EB"/></a:hlink>
   <a:folHlink><a:srgbClr val="7C3AED"/></a:folHlink>
  </a:clrScheme>
  <a:fontScheme name="GFTD">
   <a:majorFont><a:latin typeface="Inter"/><a:ea typeface="Noto Sans JP"/><a:cs typeface=""/></a:majorFont>
   <a:minorFont><a:latin typeface="Inter"/><a:ea typeface="Noto Sans JP"/><a:cs typeface=""/></a:minorFont>
  </a:fontScheme>
  <a:fmtScheme name="GFTD"/>
 </a:themeElements>
</a:theme>`;

const master1 = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:sldMaster xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"
 xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
 xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" name="GFTD Master">
 <p:clrMap bg1="lt1" tx1="dk2" bg2="lt2" tx2="dk1" accent1="accent1" accent2="accent2" accent3="accent3" accent4="accent4" accent5="accent5" accent6="accent6" hlink="hlink" folHlink="folHlink"/>
 <p:cSld>
  <p:bg><p:bgPr><a:solidFill><a:schemeClr val="bg1"/></a:solidFill></p:bgPr></p:bg>
  <p:spTree>
   <p:sp><p:nvSpPr><p:nvPr><p:ph type="title"/></p:nvPr></p:nvSpPr>
    <p:spPr><a:xfrm><a:off x="838200" y="365125"/><a:ext cx="10515600" cy="1325563"/></a:xfrm></p:spPr></p:sp>
   <p:sp><p:nvSpPr><p:nvPr><p:ph type="body" idx="1"/></p:nvPr></p:nvSpPr>
    <p:spPr><a:xfrm><a:off x="838200" y="1825625"/><a:ext cx="10515600" cy="4351338"/></a:xfrm></p:spPr></p:sp>
  </p:spTree>
 </p:cSld>
 <p:txStyles>
  <p:titleStyle>
   <a:lvl1pPr algn="l"><a:defRPr sz="4400" b="1"><a:solidFill><a:schemeClr val="dk2"/></a:solidFill><a:latin typeface="+mj-lt"/></a:defRPr></a:lvl1pPr>
  </p:titleStyle>
  <p:bodyStyle>
   <a:lvl1pPr algn="l"><a:defRPr sz="2000"><a:solidFill><a:schemeClr val="tx1"/></a:solidFill><a:latin typeface="+mn-lt"/></a:defRPr></a:lvl1pPr>
   <a:lvl2pPr algn="l"><a:defRPr sz="1800"><a:solidFill><a:schemeClr val="dk1"/></a:solidFill></a:defRPr></a:lvl2pPr>
  </p:bodyStyle>
  <p:otherStyle><a:lvl1pPr><a:defRPr sz="1800"/></a:lvl1pPr></p:otherStyle>
 </p:txStyles>
</p:sldMaster>`;

const masterRels = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
 <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme" Target="../theme/theme1.xml"/>
</Relationships>`;

const layout1 = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:sldLayout xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"
 xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" type="title" name="Title Slide">
 <p:cSld>
  <p:spTree>
   <p:sp><p:nvSpPr><p:nvPr><p:ph type="ctrTitle"/></p:nvPr></p:nvSpPr>
    <p:spPr><a:xfrm><a:off x="838200" y="2174875"/><a:ext cx="10515600" cy="1655762"/></a:xfrm></p:spPr></p:sp>
   <p:sp><p:nvSpPr><p:nvPr><p:ph type="subTitle" idx="1"/></p:nvPr></p:nvSpPr>
    <p:spPr><a:xfrm><a:off x="1инструменти838200" y="3886200"/><a:ext cx="10515600" cy="1101725"/></a:xfrm></p:spPr></p:sp>
  </p:spTree>
 </p:cSld>
</p:sldLayout>`.replace("1инструменти838200", "838200");

const layoutRels = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
 <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster" Target="../slideMasters/slideMaster1.xml"/>
</Relationships>`;

const slide1 = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"
 xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
 xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
 <p:cSld>
  <p:spTree>
   <p:sp>
    <p:nvSpPr><p:nvPr><p:ph type="title"/></p:nvPr></p:nvSpPr>
    <p:spPr><a:xfrm><a:off x="838200" y="365125"/><a:ext cx="10515600" cy="1325563"/></a:xfrm></p:spPr>
    <p:txBody><a:bodyPr/><a:p><a:r><a:rPr lang="ja-JP" sz="4400" b="1"><a:solidFill><a:schemeClr val="tx2"/></a:solidFill><a:latin typeface="+mj-lt"/></a:rPr><a:t>四半期業績レビュー</a:t></a:r></a:p></p:txBody>
   </p:sp>
   <p:sp>
    <p:nvSpPr><p:nvPr><p:ph type="body" idx="1"/></p:nvPr></p:nvSpPr>
    <p:spPr><a:xfrm><a:off x="838200" y="1825625"/><a:ext cx="10515600" cy="4351338"/></a:xfrm></p:spPr>
    <p:txBody><a:bodyPr/>
     <a:p><a:r><a:rPr lang="ja-JP" sz="2000"><a:latin typeface="+mn-lt"/></a:rPr><a:t>・売上は前年同期比 +18%</a:t></a:r></a:p>
     <a:p><a:r><a:t>・継承テスト: rPr 無し (master body を継承)</a:t></a:r></a:p>
    </p:txBody>
   </p:sp>
   <p:sp>
    <p:nvSpPr><p:nvPr/></p:nvSpPr>
    <p:spPr><a:xfrm><a:off x="838200" y="4800600"/><a:ext cx="1143000" cy="1143000"/></a:xfrm>
     <a:prstGeom prst="ellipse"/><a:solidFill><a:schemeClr val="accent2"/></a:solidFill></p:spPr>
    <p:txBody><a:bodyPr/><a:p/></p:txBody>
   </p:sp>
   <p:sp>
    <p:nvSpPr><p:nvPr/></p:nvSpPr>
    <p:spPr><a:xfrm><a:off x="9525000" y="5715000"/><a:ext cx="2286000" cy="762000"/></a:xfrm>
     <a:prstGeom prst="roundRect"/><a:solidFill><a:schemeClr val="accent1"/></a:solidFill></p:spPr>
    <p:txBody><a:bodyPr/><a:p><a:r><a:rPr lang="en-US" sz="1600" b="1"><a:solidFill><a:schemeClr val="bg1"/></a:solidFill></a:rPr><a:t>GFTD</a:t></a:r></a:p></p:txBody>
   </p:sp>
   <p:pic>
    <p:nvPicPr><p:cNvPr id="9" name="Picture 1"/><p:cNvPicPr/><p:nvPr/></p:nvPicPr>
    <p:blipFill><a:blip r:embed="rId1"/></p:blipFill>
    <p:spPr><a:xfrm><a:off x="6858000" y="2057400"/><a:ext cx="3429000" cy="2286000"/></a:xfrm></p:spPr>
   </p:pic>
  </p:spTree>
 </p:cSld>
</p:sld>`;

const slide1Rels = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
 <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout" Target="../slideLayouts/slideLayout1.xml"/>
</Relationships>`;

const slide2 = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"
 xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">
 <p:cSld>
  <p:spTree>
   <p:sp>
    <p:nvSpPr><p:nvPr><p:ph type="title"/></p:nvPr></p:nvSpPr>
    <p:spPr><a:xfrm><a:off x="838200" y="365125"/><a:ext cx="10515600" cy="1325563"/></a:xfrm></p:spPr>
    <p:txBody><a:bodyPr/><a:p><a:r><a:rPr lang="ja-JP"/><a:t>主要指標</a:t></a:r></a:p></p:txBody>
   </p:sp>
   <p:sp>
    <p:nvSpPr><p:nvPr/></p:nvSpPr>
    <p:spPr><a:xfrm rot="2700000"><a:off x="838200" y="2200000"/><a:ext cx="2400000" cy="900000"/></a:xfrm>
     <a:prstGeom prst="rect"/>
     <a:gradFill><a:gsLst>
       <a:gs pos="0"><a:schemeClr val="accent1"/></a:gs>
       <a:gs pos="100000"><a:schemeClr val="accent5"/></a:gs>
     </a:gsLst><a:lin ang="2700000"/></a:gradFill></p:spPr>
    <p:txBody><a:bodyPr/><a:p><a:r><a:rPr lang="en-US" b="1"><a:solidFill><a:schemeClr val="bg1"/></a:solidFill></a:rPr><a:t>45°</a:t></a:r></a:p></p:txBody>
   </p:sp>
   <p:graphicFrame>
    <p:nvGraphicFramePr><p:cNvPr id="5" name="Table 1"/><p:cNvGraphicFramePr/><p:nvPr/></p:nvGraphicFramePr>
    <p:xfrm><a:off x="4500000" y="2200000"/><a:ext cx="6000000" cy="1800000"/></p:xfrm>
    <a:graphic><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/table">
     <a:tbl>
      <a:tblGrid><a:gridCol w="3000000"/><a:gridCol w="3000000"/></a:tblGrid>
      <a:tr h="600000">
       <a:tc><a:txBody><a:bodyPr/><a:p><a:r><a:rPr lang="ja-JP" b="1"/><a:t>指標</a:t></a:r></a:p></a:txBody><a:tcPr><a:solidFill><a:schemeClr val="accent1"/></a:solidFill></a:tcPr></a:tc>
       <a:tc><a:txBody><a:bodyPr/><a:p><a:r><a:rPr lang="ja-JP" b="1"/><a:t>値</a:t></a:r></a:p></a:txBody><a:tcPr><a:solidFill><a:schemeClr val="accent1"/></a:solidFill></a:tcPr></a:tc>
      </a:tr>
      <a:tr h="600000">
       <a:tc><a:txBody><a:bodyPr/><a:p><a:r><a:rPr lang="ja-JP"/><a:t>売上</a:t></a:r></a:p></a:txBody><a:tcPr/></a:tc>
       <a:tc><a:txBody><a:bodyPr/><a:p><a:r><a:rPr lang="en-US"/><a:t>+18%</a:t></a:r></a:p></a:txBody><a:tcPr/></a:tc>
      </a:tr>
     </a:tbl>
    </a:graphicData></a:graphic>
   </p:graphicFrame>
  </p:spTree>
 </p:cSld>
</p:sld>`;

const slide2Rels = slide1Rels;

// ── 2 つ目の master / theme / layout / slide (複数 master 検証用) ──
const theme2 = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<a:theme xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" name="GFTD Alt">
 <a:themeElements>
  <a:clrScheme name="GFTD Alt">
   <a:dk1><a:sysClr val="windowText" lastClr="111827"/></a:dk1>
   <a:lt1><a:sysClr val="window" lastClr="FFFFFF"/></a:lt1>
   <a:dk2><a:srgbClr val="7C2D12"/></a:dk2>
   <a:lt2><a:srgbClr val="FFF7ED"/></a:lt2>
   <a:accent1><a:srgbClr val="DB2777"/></a:accent1>
   <a:accent2><a:srgbClr val="F97316"/></a:accent2>
   <a:accent3><a:srgbClr val="EAB308"/></a:accent3>
   <a:accent4><a:srgbClr val="22C55E"/></a:accent4>
   <a:accent5><a:srgbClr val="0EA5E9"/></a:accent5>
   <a:accent6><a:srgbClr val="6366F1"/></a:accent6>
   <a:hlink><a:srgbClr val="DB2777"/></a:hlink>
   <a:folHlink><a:srgbClr val="9D174D"/></a:folHlink>
  </a:clrScheme>
  <a:fontScheme name="GFTD Alt">
   <a:majorFont><a:latin typeface="Roboto Slab"/><a:ea typeface="Noto Serif JP"/><a:cs typeface=""/></a:majorFont>
   <a:minorFont><a:latin typeface="Roboto"/><a:ea typeface="Noto Sans JP"/><a:cs typeface=""/></a:minorFont>
  </a:fontScheme>
  <a:fmtScheme name="GFTD Alt"/>
 </a:themeElements>
</a:theme>`;

const master2 = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:sldMaster xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"
 xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
 xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" name="GFTD Alt Master">
 <p:clrMap bg1="lt1" tx1="dk2" bg2="lt2" tx2="dk1" accent1="accent1" accent2="accent2" accent3="accent3" accent4="accent4" accent5="accent5" accent6="accent6" hlink="hlink" folHlink="folHlink"/>
 <p:cSld>
  <p:bg><p:bgPr><a:solidFill><a:schemeClr val="lt2"/></a:solidFill></p:bgPr></p:bg>
  <p:spTree>
   <p:sp><p:nvSpPr><p:nvPr><p:ph type="title"/></p:nvPr></p:nvSpPr>
    <p:spPr><a:xfrm><a:off x="838200" y="365125"/><a:ext cx="10515600" cy="1325563"/></a:xfrm></p:spPr></p:sp>
  </p:spTree>
 </p:cSld>
 <p:txStyles>
  <p:titleStyle><a:lvl1pPr algn="l"><a:defRPr sz="4000" b="1"><a:solidFill><a:schemeClr val="tx1"/></a:solidFill><a:latin typeface="+mj-lt"/></a:defRPr></a:lvl1pPr></p:titleStyle>
  <p:bodyStyle><a:lvl1pPr><a:defRPr sz="1800"><a:latin typeface="+mn-lt"/></a:defRPr></a:lvl1pPr></p:bodyStyle>
  <p:otherStyle><a:lvl1pPr><a:defRPr sz="1800"/></a:lvl1pPr></p:otherStyle>
 </p:txStyles>
</p:sldMaster>`;

const master2Rels = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
 <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme" Target="../theme/theme2.xml"/>
</Relationships>`;

const layout2 = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:sldLayout xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"
 xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" type="obj" name="Alt Content">
 <p:cSld><p:spTree>
  <p:sp><p:nvSpPr><p:nvPr><p:ph type="title"/></p:nvPr></p:nvSpPr>
   <p:spPr><a:xfrm><a:off x="838200" y="365125"/><a:ext cx="10515600" cy="1325563"/></a:xfrm></p:spPr></p:sp>
 </p:spTree></p:cSld>
</p:sldLayout>`;

const layout2Rels = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
 <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster" Target="../slideMasters/slideMaster2.xml"/>
</Relationships>`;

const slide3 = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"
 xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">
 <p:cSld><p:spTree>
   <p:sp><p:nvSpPr><p:nvPr><p:ph type="title"/></p:nvPr></p:nvSpPr>
    <p:spPr><a:xfrm><a:off x="838200" y="365125"/><a:ext cx="10515600" cy="1325563"/></a:xfrm></p:spPr>
    <p:txBody><a:bodyPr/><a:p><a:r><a:rPr lang="ja-JP"/><a:t>別マスターのスライド</a:t></a:r></a:p></p:txBody></p:sp>
   <p:sp><p:nvSpPr><p:nvPr/></p:nvSpPr>
    <p:spPr><a:xfrm><a:off x="838200" y="2200000"/><a:ext cx="1600000" cy="800000"/></a:xfrm>
     <a:prstGeom prst="rect"/><a:solidFill><a:schemeClr val="accent1"/></a:solidFill></p:spPr>
    <p:txBody><a:bodyPr/><a:p/></p:txBody></p:sp>
 </p:spTree></p:cSld>
</p:sld>`;

const slide3Rels = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
 <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout" Target="../slideLayouts/slideLayout2.xml"/>
</Relationships>`;

const presentation = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:presentation xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"
 xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
 xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
 <p:sldIdLst><p:sldId id="256" r:id="rId2"/><p:sldId id="257" r:id="rId3"/><p:sldId id="258" r:id="rId4"/></p:sldIdLst>
 <p:sldSz cx="12192000" cy="6858000" type="screen16x9"/>
 <p:notesSz cx="6858000" cy="9144000"/>
</p:presentation>`;

const presentationRels = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
 <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster" Target="slideMasters/slideMaster1.xml"/>
 <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide" Target="slides/slide1.xml"/>
 <Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide" Target="slides/slide2.xml"/>
 <Relationship Id="rId4" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide" Target="slides/slide3.xml"/>
 <Relationship Id="rId6" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster" Target="slideMasters/slideMaster2.xml"/>
</Relationships>`;

const viewProps = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:viewPr xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">
 <p:cSldViewPr>
  <p:guideLst>
   <p:guide orient="horz" pos="2160"/>
   <p:guide orient="vert" pos="2880"/>
   <p:guide orient="vert" pos="5760"/>
  </p:guideLst>
 </p:cSldViewPr>
</p:viewPr>`;

const contentTypes = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
 <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
 <Default Extension="xml" ContentType="application/xml"/>
</Types>`;

const files = {
  "[Content_Types].xml": strToU8(contentTypes),
  "ppt/presentation.xml": strToU8(presentation),
  "ppt/_rels/presentation.xml.rels": strToU8(presentationRels),
  "ppt/viewProps.xml": strToU8(viewProps),
  "ppt/theme/theme1.xml": strToU8(theme1),
  "ppt/slideMasters/slideMaster1.xml": strToU8(master1),
  "ppt/slideMasters/_rels/slideMaster1.xml.rels": strToU8(masterRels),
  "ppt/slideLayouts/slideLayout1.xml": strToU8(layout1),
  "ppt/slideLayouts/_rels/slideLayout1.xml.rels": strToU8(layoutRels),
  "ppt/slides/slide1.xml": strToU8(slide1),
  "ppt/slides/_rels/slide1.xml.rels": strToU8(slide1Rels),
  "ppt/slides/slide2.xml": strToU8(slide2),
  "ppt/slides/_rels/slide2.xml.rels": strToU8(slide2Rels),
  "ppt/theme/theme2.xml": strToU8(theme2),
  "ppt/slideMasters/slideMaster2.xml": strToU8(master2),
  "ppt/slideMasters/_rels/slideMaster2.xml.rels": strToU8(master2Rels),
  "ppt/slideLayouts/slideLayout2.xml": strToU8(layout2),
  "ppt/slideLayouts/_rels/slideLayout2.xml.rels": strToU8(layout2Rels),
  "ppt/slides/slide3.xml": strToU8(slide3),
  "ppt/slides/_rels/slide3.xml.rels": strToU8(slide3Rels),
};

mkdirSync(dirname(out), { recursive: true });
writeFileSync(out, zipSync(files));
console.log("wrote", out);
