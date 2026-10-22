package com.example.noiserecorder

import java.io.*
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

sealed class ReportBlock {
    data class Heading(val text: String, val pageBreak: Boolean = false) : ReportBlock()
    data class Paragraph(val text: String) : ReportBlock()
    data class Table(val headers: List<String>, val rows: List<List<String>>) : ReportBlock()
    data class Image(val name: String, val description: String) : ReportBlock()
    data class Audio(val name: String) : ReportBlock()
}

/** Both renderers consume the same content, statistics and original audio snapshot. */
class ReportBuilder(private val report: NoiseReport, private val audioDirectory: File) {
    fun generate(root: File, progress: (String) -> Unit = {}) {
        check(root.mkdirs() || root.isDirectory)
        val available = report.recordings.filter { it.evidence.status !in setOf("deleted", "deleting", "missing") && File(audioDirectory, it.evidence.name).isFile }
        val bytes = available.sumOf { File(audioDirectory, it.evidence.name).length() }
        check(bytes <= 400L * 1024 * 1024) { "所选音频超过 400 MB，请分批选择时段生成报告，以保证 Word 可打开" }
        check(root.usableSpace > bytes * 2 + 32L * 1024 * 1024) { "生成报告空间不足，请减少所选时段或释放存储" }
        val audioRoot = File(root, "audio").apply { mkdirs() }
        val hashes = linkedMapOf<String, String>()
        available.forEachIndexed { index, row ->
            progress("整理音频 ${index + 1} / ${available.size}")
            val name = row.evidence.name
            require(File(audioDirectory, name).canonicalFile.parentFile == audioDirectory.canonicalFile)
            val digest = MessageDigest.getInstance("SHA-256")
            java.security.DigestInputStream(File(audioDirectory, name).inputStream(), digest).use { input -> File(audioRoot,name).outputStream().use { input.copyTo(it) } }
            hashes[name] = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        File(root,"SHA256SUMS.txt").writeText(hashes.entries.joinToString("\n", postfix = "\n") { "${it.value}  audio/${it.key}" })
        progress("绘制声级曲线")
        report.sessions.forEach { ReportChart.draw(it,report.zone,File(root,"curve_${it.session.id}.png")) }
        ReportChart.audioIcon(File(root,"audio_icon.png"))
        val blocks = content(hashes)
        progress("生成 HTML 报告")
        writeHtml(root,blocks)
        progress("生成 Word 报告与音频附件")
        writeWord(root,blocks)
        File(root,"README.txt").writeText("解压后打开 report.html，audio 文件夹需保持相对位置。report.docx 已嵌入原始 WAV，可单独复制到电脑。Windows 桌面 Word 通常可双击附件调用播放器；手机、网页版及其他阅读器可能不支持 OLE，改用 HTML 或 audio 文件夹。HTML 为本地报告，不依赖外部网络。手机应用也提供浏览器预览及独立 HTML 导出。删除应用内原始录音不会删除已生成或已导出的报告副本。\n")
        File(root,"READY").writeText(report.generated.toString())
    }
    private fun content(hashes: Map<String,String>): List<ReportBlock> = buildList {
        val retained = hashes.size
        val deleted = report.recordings.count { it.evidence.status in setOf("deleted", "deleting") }
        val measured = report.observedMs > 0
        add(ReportBlock.Heading("环境声音监测报告"))
        add(ReportBlock.Paragraph(if(measured) "本报告涵盖所选 ${report.sessions.size} 个实际监听时段，共记录 ${report.eventCount} 次连续超限，超限总时长 ${ReportFormat.seconds(report.exceededMs)}。包含可播放录音 $retained 段，已删除录音 $deleted 段。" else "本报告包含可播放录音 $retained 段，已删除录音 $deleted 段。所选资料没有有效声级样本，完整监测时长、超限次数和超限时长均无法统计。"))
        add(ReportBlock.Paragraph("生成时间：${report.time(report.generated)}；时区：${report.zone.id}。记录时间来自手机系统时钟。"))
        add(ReportBlock.Table(listOf("项目", "结果"),listOf(
            listOf("有效声级监测时长",if(measured) ReportFormat.seconds(report.observedMs) else "无数据"),
            listOf("连续超限事件", if(measured) "${report.eventCount} 次" else "无数据，无法统计"),
            listOf("超限总时长",if(measured) ReportFormat.seconds(report.exceededMs) else "无数据"),
            listOf("超限占比",if(report.observedMs == 0L) "无数据" else ReportFormat.number(report.exceededMs * 100.0 / report.observedMs) + "%"),
            listOf("最高估算声级",ReportFormat.number(report.sessions.mapNotNull { it.peak }.maxOrNull()) + " dB"),
            listOf("可播放录音 / 已删除录音","$retained / $deleted"))))
        add(ReportBlock.Heading("统计与测量说明"))
        add(ReportBlock.Paragraph("连续达到或超过该时段阈值记为一次超限；任一约 100 ms 音频帧回落到阈值以下即结束该次事件。次数与录音段数不同，反复触发可以延长同一段录音。超限时长按实际采集帧累计，声级曲线按约一秒聚合，保留最小值、最大值和能量平均值。无采集样本的区间不作为安静时段计入。"))
        add(ReportBlock.Paragraph("声级为 dBFS 加校准偏移得到的估算 dB SPL，未做 A/C 频率计权；峰值表示 100 ms RMS 声级的最大值。默认偏移 90 dB 未经校准。报告不将手机估算值等同于专业声级计读数。监听时段仅表示实际采集范围，非用户计划范围。"))
        add(ReportBlock.Paragraph("HTML 点击播放器播放；Word 中双击音频附件，在支持 OLE 的 Windows 桌面版 Word 中调用关联播放器。部分 Office 安全策略或阅读器会禁用此功能，可使用报告包中的 audio 文件夹。附件不自动播放。SHA-256 校验原始音频字节。"))
        report.sessions.forEachIndexed { index, item ->
            val s = item.session
            add(ReportBlock.Heading("监听时段 ${index + 1}",true))
            add(ReportBlock.Paragraph("${report.time(s.start)} 至 ${report.time(s.end)}；状态：${s.status}。"))
            add(ReportBlock.Table(listOf("指标", "数值"),listOf(
                listOf("阈值 / 校准偏移","${ReportFormat.number(s.threshold)} / ${ReportFormat.number(s.offset)} dB"),
                listOf("有效样本时长 / 超限时长","${ReportFormat.seconds(item.observedMs)} / ${ReportFormat.seconds(item.exceededMs)}"),
                listOf("超限事件数", "${item.events.size} 次"),
                listOf("最高 / 能量平均估算声级","${ReportFormat.number(item.peak)} / ${ReportFormat.number(item.mean)} dB"))))
            add(ReportBlock.Image("curve_${s.id}.png","声级随时间变化及阈值线"))
            add(ReportBlock.Heading("超限时间明细"))
            if (item.events.isEmpty()) add(ReportBlock.Paragraph(if(item.samples.isEmpty()) "无声级样本，无法判断是否超限。" else "该时段没有记录到超限事件。"))
            else add(ReportBlock.Table(listOf("开始时间","结束时间","超限时长","峰值 dB"),item.events.map { listOf(report.time(it.start).replace(' ','\n'),report.time(it.end).replace(' ','\n'),ReportFormat.seconds(it.duration),ReportFormat.number(it.peak)) }))
            recordings(report.recordings.filter { it.sessionId == s.id },hashes,this)
        }
        val legacy = report.recordings.filter { it.sessionId == null }
        if (legacy.isNotEmpty()) {
            add(ReportBlock.Heading("旧版本录音",true))
            add(ReportBlock.Paragraph("这些录音没有完整监听时段、未触发时的声级样本或连续超限事件记录。不能据此推算完整监测时长、超限次数和曲线，也未纳入前述超限统计。"))
            recordings(legacy,hashes,this)
        }
    }
    private fun recordings(rows: List<ReportRecording>, hashes: Map<String,String>, output: MutableList<ReportBlock>) {
        output.add(ReportBlock.Heading("录音清单"))
        if(rows.isEmpty()) output.add(ReportBlock.Paragraph("该时段没有录音。"))
        rows.forEach { row ->
            val e = row.evidence
            output.add(ReportBlock.Paragraph("文件：${e.name}\n触发时间（UTC）：${e.started}\n时长：${ReportFormat.number(e.duration)} 秒；峰值：${ReportFormat.number(e.peak)} dB；状态：${e.status}。\n阈值 / 校准偏移：${ReportFormat.number(row.threshold)} / ${ReportFormat.number(row.offset)} dB。"))
            hashes[e.name]?.let { hash -> output.add(ReportBlock.Paragraph("SHA-256：$hash")); output.add(ReportBlock.Audio(e.name)) }
                ?: output.add(ReportBlock.Paragraph(if (e.status in setOf("deleted","deleting")) "原始录音已删除；保留监测统计，不提供此音频。" else "录音文件缺失，未嵌入报告。"))
        }
    }
    private fun writeHtml(root: File, blocks: List<ReportBlock>) {
        File(root,"report.html").bufferedWriter().use { w ->
            w.append("<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>环境声音监测报告</title><style>body{font:16px/1.7 system-ui,sans-serif;color:#192a3a;background:#f2f5f8;margin:0}main{max-width:1050px;margin:auto;padding:24px;background:white}h1{font-size:27px;color:#111}h2{font-size:21px;margin-top:32px}p{white-space:pre-wrap;overflow-wrap:anywhere}.table{overflow:auto}table{border-collapse:collapse;width:100%;margin:16px 0;font-size:14px}td,th{border:1px solid #d9e0e7;padding:10px;text-align:left;min-width:100px}th{background:#edf2f7}td{white-space:pre-line}img{max-width:100%;height:auto}audio{width:100%;margin-bottom:24px}@media print{body{background:white}main{padding:0}h2{break-after:avoid}audio{display:none}}</style></head><body><main>\n")
            blocks.forEachIndexed { index, block -> when(block) {
                is ReportBlock.Heading -> { val tag=if(index==0) "h1" else "h2"; w.append("<$tag>${ReportFormat.xml(block.text)}</$tag>\n") }
                is ReportBlock.Paragraph -> w.append("<p>${ReportFormat.xml(block.text)}</p>\n")
                is ReportBlock.Table -> { w.append("<div class=\"table\"><table><thead><tr>"); block.headers.forEach { w.append("<th>${ReportFormat.xml(it)}</th>") }; w.append("</tr></thead><tbody>"); block.rows.forEach { row -> w.append("<tr>"); row.forEach { w.append("<td>${ReportFormat.xml(it)}</td>") }; w.append("</tr>") }; w.append("</tbody></table></div>\n") }
                is ReportBlock.Image -> w.append("<img alt=\"${ReportFormat.xml(block.description)}\" src=\"data:image/png;base64,${Base64.getEncoder().encodeToString(File(root,block.name).readBytes())}\">\n")
                is ReportBlock.Audio -> w.append("<audio controls preload=\"none\" src=\"audio/${ReportFormat.xml(block.name)}\">浏览器不支持音频播放器</audio>\n")
            } }
            w.append("</main></body></html>\n")
        }
    }
    private fun writeWord(root: File, blocks: List<ReportBlock>) {
        val relationships=StringBuilder("<Relationship Id=\"styles\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>")
        var imageId=0; var audioId=0
        ZipOutputStream(File(root,"report.docx").outputStream().buffered()).use { zip ->
            fun textEntry(name:String,value:String) { zip.putNextEntry(ZipEntry(name)); zip.write(value.toByteArray(Charsets.UTF_8)); zip.closeEntry() }
            textEntry("[Content_Types].xml","""<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Default Extension="png" ContentType="image/png"/><Default Extension="bin" ContentType="application/vnd.openxmlformats-officedocument.oleObject"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/><Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/></Types>""")
            textEntry("_rels/.rels","""<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="document" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>""")
            textEntry("word/styles.xml","""<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii="Calibri" w:hAnsi="Calibri" w:eastAsia="宋体"/><w:sz w:val="21"/><w:lang w:val="zh-CN"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr><w:spacing w:after="120" w:line="300" w:lineRule="auto"/></w:pPr></w:pPrDefault></w:docDefaults><w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/></w:style><w:style w:type="paragraph" w:styleId="Title"><w:name w:val="Title"/><w:basedOn w:val="Normal"/><w:pPr><w:keepNext/><w:spacing w:after="280"/></w:pPr><w:rPr><w:b/><w:color w:val="000000"/><w:sz w:val="44"/></w:rPr></w:style><w:style w:type="paragraph" w:styleId="Heading1"><w:name w:val="heading 1"/><w:basedOn w:val="Normal"/><w:pPr><w:keepNext/><w:spacing w:before="240" w:after="140"/><w:outlineLvl w:val="0"/></w:pPr><w:rPr><w:b/><w:sz w:val="30"/></w:rPr></w:style></w:styles>""")
            zip.putNextEntry(ZipEntry("word/document.xml"))
            val w=OutputStreamWriter(zip,Charsets.UTF_8)
            w.append("""<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:v="urn:schemas-microsoft-com:vml" xmlns:o="urn:schemas-microsoft-com:office:office" xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture"><w:body>""")
            fun paragraph(text:String,style:String="Normal",pageBreak:Boolean=false) {
                w.append("<w:p><w:pPr><w:pStyle w:val=\"$style\"/>"); if(pageBreak) w.append("<w:pageBreakBefore/>"); w.append("</w:pPr><w:r>")
                text.split('\n').forEachIndexed { i,line -> if(i>0) w.append("<w:br/>"); w.append("<w:t xml:space=\"preserve\">${ReportFormat.xml(line)}</w:t>") }; w.append("</w:r></w:p>")
            }
            blocks.forEachIndexed { index, block -> when(block) {
                is ReportBlock.Heading -> paragraph(block.text,if(index==0) "Title" else "Heading1",block.pageBreak)
                is ReportBlock.Paragraph -> paragraph(block.text)
                is ReportBlock.Table -> {
                    val width=9638/block.headers.size
                    w.append("<w:tbl><w:tblPr><w:tblW w:w=\"9638\" w:type=\"dxa\"/><w:tblBorders>"); listOf("top","left","bottom","right","insideH","insideV").forEach { w.append("<w:$it w:val=\"single\" w:sz=\"4\" w:color=\"D9E0E7\"/>") }; w.append("</w:tblBorders><w:tblCellMar><w:top w:w=\"90\" w:type=\"dxa\"/><w:left w:w=\"90\" w:type=\"dxa\"/><w:bottom w:w=\"90\" w:type=\"dxa\"/><w:right w:w=\"90\" w:type=\"dxa\"/></w:tblCellMar></w:tblPr><w:tblGrid>")
                    block.headers.forEach { w.append("<w:gridCol w:w=\"$width\"/>") }; w.append("</w:tblGrid>")
                    (listOf(block.headers)+block.rows).forEachIndexed { rowIndex,row ->
                        w.append("<w:tr><w:trPr><w:cantSplit/>"); if(rowIndex==0) w.append("<w:tblHeader/>"); w.append("</w:trPr>")
                        row.forEach { cell -> w.append("<w:tc><w:tcPr><w:tcW w:w=\"$width\" w:type=\"dxa\"/>"); if(rowIndex==0) w.append("<w:shd w:fill=\"EDF2F7\"/>"); w.append("</w:tcPr>"); paragraph(cell); w.append("</w:tc>") }; w.append("</w:tr>")
                    }; w.append("</w:tbl>")
                }
                is ReportBlock.Image -> {
                    imageId++; val id="img$imageId"
                    relationships.append("<Relationship Id=\"$id\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"media/${block.name}\"/>")
                    w.append("""<w:p><w:r><w:drawing><wp:inline><wp:extent cx="6000000" cy="2100000"/><wp:docPr id="$imageId" name="${block.name}" descr="${ReportFormat.xml(block.description)}"/><a:graphic><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/picture"><pic:pic><pic:nvPicPr><pic:cNvPr id="$imageId" name="${block.name}"/><pic:cNvPicPr/></pic:nvPicPr><pic:blipFill><a:blip r:embed="$id"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill><pic:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="6000000" cy="2100000"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></pic:spPr></pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>""")
                }
                is ReportBlock.Audio -> {
                    audioId++
                    val shape="_x0000_i${1024+audioId}"
                    relationships.append("<Relationship Id=\"ole$audioId\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/oleObject\" Target=\"embeddings/audio$audioId.bin\"/>")
                    w.append("""<w:p><w:r><w:object w:dxaOrig="3600" w:dyaOrig="750"><v:shape id="$shape" type="#_x0000_t75" style="width:180pt;height:37.5pt" o:ole=""><v:imagedata r:id="audioIcon" o:title="${ReportFormat.xml(block.name)}"/></v:shape><o:OLEObject Type="Embed" ProgID="Package" ShapeID="$shape" DrawAspect="Icon" ObjectID="_${report.generated}$audioId" r:id="ole$audioId"/></w:object></w:r></w:p>""")
                }
            } }
            w.append("<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"1134\" w:right=\"1134\" w:bottom=\"1134\" w:left=\"1134\" w:header=\"0\" w:footer=\"0\" w:gutter=\"0\"/></w:sectPr></w:body></w:document>")
            w.flush(); zip.closeEntry()
            relationships.append("<Relationship Id=\"audioIcon\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"media/audio_icon.png\"/>")
            textEntry("word/_rels/document.xml.rels","<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">$relationships</Relationships>")
            (blocks.filterIsInstance<ReportBlock.Image>().map { it.name } + "audio_icon.png").distinct().forEach { name -> zip.putNextEntry(ZipEntry("word/media/$name")); File(root,name).inputStream().use { it.copyTo(zip) }; zip.closeEntry() }
            blocks.filterIsInstance<ReportBlock.Audio>().forEachIndexed { i, audio -> zip.putNextEntry(ZipEntry("word/embeddings/audio${i+1}.bin")); OlePackage.write(File(root,"audio/${audio.name}"),zip); zip.closeEntry() }
        }
    }
    companion object {
        fun exportZip(root: File, output: OutputStream) {
            ZipOutputStream(output.buffered()).use { zip -> root.walkTopDown().filter { it.isFile && it.name !in setOf("READY","audio_icon.png") }.forEach { file -> zip.putNextEntry(ZipEntry(file.relativeTo(root).invariantSeparatorsPath)); file.inputStream().use { it.copyTo(zip) }; zip.closeEntry() } }
        }
        /** Standalone HTML can be moved independently; large audio is base64 streamed. */
        fun exportHtml(root: File, output: OutputStream) {
            output.buffered().use { out ->
                File(root,"report.html").bufferedReader().useLines { lines -> lines.forEach { line ->
                    val marker="src=\"audio/"; val at=line.indexOf(marker)
                    if(at < 0) out.write((line+"\n").toByteArray(Charsets.UTF_8))
                    else {
                        val begin=at+marker.length; val end=line.indexOf('"',begin)
                        val name=line.substring(begin,end)
                        require(File(root,"audio/$name").canonicalFile.parentFile == File(root,"audio").canonicalFile)
                        out.write((line.substring(0,at)+"src=\"data:audio/wav;base64,").toByteArray(Charsets.UTF_8))
                        val nonClosing=object:FilterOutputStream(out) { override fun close() { flush() } }
                        Base64.getEncoder().wrap(nonClosing).use { encoded -> File(root,"audio/$name").inputStream().use { it.copyTo(encoded) } }
                        out.write((line.substring(end)+"\n").toByteArray(Charsets.UTF_8))
                    }
                } }
            }
        }
    }
}
