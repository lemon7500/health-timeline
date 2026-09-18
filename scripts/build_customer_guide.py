from __future__ import annotations

from pathlib import Path

from docx import Document
from docx.enum.section import WD_SECTION
from docx.enum.table import WD_CELL_VERTICAL_ALIGNMENT, WD_TABLE_ALIGNMENT
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
from docx.shared import Inches, Pt, RGBColor


ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "dist" / "病程日历-1.3.0-客户使用指南.docx"

BLUE = "2E74B5"
DARK_BLUE = "1F4D78"
PALE_BLUE = "E8EEF5"
PALE_YELLOW = "FFF4CE"
PALE_GREEN = "E8F5E9"
TEXT = RGBColor(38, 45, 52)


def set_cell_shading(cell, fill: str) -> None:
    tc_pr = cell._tc.get_or_add_tcPr()
    shd = tc_pr.find(qn("w:shd"))
    if shd is None:
        shd = OxmlElement("w:shd")
        tc_pr.append(shd)
    shd.set(qn("w:fill"), fill)


def set_cell_margins(cell, top=100, start=130, bottom=100, end=130) -> None:
    tc = cell._tc
    tc_pr = tc.get_or_add_tcPr()
    tc_mar = tc_pr.first_child_found_in("w:tcMar")
    if tc_mar is None:
        tc_mar = OxmlElement("w:tcMar")
        tc_pr.append(tc_mar)
    for key, value in (("top", top), ("start", start), ("bottom", bottom), ("end", end)):
        node = tc_mar.find(qn(f"w:{key}"))
        if node is None:
            node = OxmlElement(f"w:{key}")
            tc_mar.append(node)
        node.set(qn("w:w"), str(value))
        node.set(qn("w:type"), "dxa")


def set_repeat_table_header(row) -> None:
    tr_pr = row._tr.get_or_add_trPr()
    tbl_header = OxmlElement("w:tblHeader")
    tbl_header.set(qn("w:val"), "true")
    tr_pr.append(tbl_header)


def set_keep_with_next(paragraph, keep: bool = True) -> None:
    paragraph.paragraph_format.keep_with_next = keep


def set_run_font(run, size=None, bold=None, color=None, east_asia="Microsoft YaHei") -> None:
    run.font.name = "Calibri"
    run._element.rPr.rFonts.set(qn("w:eastAsia"), east_asia)
    if size is not None:
        run.font.size = Pt(size)
    if bold is not None:
        run.bold = bold
    if color is not None:
        run.font.color.rgb = RGBColor.from_string(color)


def add_page_field(paragraph) -> None:
    paragraph.add_run("第 ")
    fld = OxmlElement("w:fldSimple")
    fld.set(qn("w:instr"), "PAGE")
    paragraph._p.append(fld)
    paragraph.add_run(" 页")


def add_callout(doc: Document, title: str, body: str, fill=PALE_YELLOW) -> None:
    heading = doc.add_paragraph()
    heading.paragraph_format.space_before = Pt(7)
    heading.paragraph_format.space_after = Pt(2)
    heading.paragraph_format.keep_with_next = True
    r = heading.add_run(title)
    set_run_font(r, 10.5, True, "000000")
    body_paragraph = doc.add_paragraph(body)
    body_paragraph.paragraph_format.space_after = Pt(7)


def add_bullets(doc: Document, items: list[str]) -> None:
    for item in items:
        p = doc.add_paragraph(style="List Bullet")
        p.add_run(item)


def create_numbering_instance(doc: Document) -> int:
    numbering = doc.part.numbering_part.element
    abstract_ids = [
        int(node.get(qn("w:abstractNumId")))
        for node in numbering.findall(qn("w:abstractNum"))
    ]
    num_ids = [
        int(node.get(qn("w:numId")))
        for node in numbering.findall(qn("w:num"))
    ]
    abstract_id = (max(abstract_ids) + 1) if abstract_ids else 1
    num_id = (max(num_ids) + 1) if num_ids else 1

    abstract = OxmlElement("w:abstractNum")
    abstract.set(qn("w:abstractNumId"), str(abstract_id))
    multi = OxmlElement("w:multiLevelType")
    multi.set(qn("w:val"), "singleLevel")
    abstract.append(multi)
    lvl = OxmlElement("w:lvl")
    lvl.set(qn("w:ilvl"), "0")
    start = OxmlElement("w:start")
    start.set(qn("w:val"), "1")
    num_fmt = OxmlElement("w:numFmt")
    num_fmt.set(qn("w:val"), "decimal")
    lvl_text = OxmlElement("w:lvlText")
    lvl_text.set(qn("w:val"), "%1.")
    suff = OxmlElement("w:suff")
    suff.set(qn("w:val"), "tab")
    p_pr = OxmlElement("w:pPr")
    tabs = OxmlElement("w:tabs")
    tab = OxmlElement("w:tab")
    tab.set(qn("w:val"), "num")
    tab.set(qn("w:pos"), "540")
    tabs.append(tab)
    ind = OxmlElement("w:ind")
    ind.set(qn("w:left"), "540")
    ind.set(qn("w:hanging"), "270")
    p_pr.append(tabs)
    p_pr.append(ind)
    lvl.extend([start, num_fmt, lvl_text, suff, p_pr])
    abstract.append(lvl)
    numbering.insert(0, abstract)

    num = OxmlElement("w:num")
    num.set(qn("w:numId"), str(num_id))
    abstract_ref = OxmlElement("w:abstractNumId")
    abstract_ref.set(qn("w:val"), str(abstract_id))
    num.append(abstract_ref)
    numbering.append(num)
    return num_id


def add_steps(doc: Document, items: list[str]) -> None:
    num_id = create_numbering_instance(doc)
    for item in items:
        p = doc.add_paragraph(style="List Number")
        p_pr = p._p.get_or_add_pPr()
        num_pr = OxmlElement("w:numPr")
        ilvl = OxmlElement("w:ilvl")
        ilvl.set(qn("w:val"), "0")
        num_id_node = OxmlElement("w:numId")
        num_id_node.set(qn("w:val"), str(num_id))
        num_pr.extend([ilvl, num_id_node])
        p_pr.append(num_pr)
        p.add_run(item)


def add_heading(doc: Document, text: str, level=1) -> None:
    p = doc.add_heading(text, level=level)
    set_keep_with_next(p)


def add_body(doc: Document, text: str) -> None:
    doc.add_paragraph(text)


def add_kv_table(doc: Document, rows: list[tuple[str, str]], widths=(1.45, 4.9)) -> None:
    table = doc.add_table(rows=0, cols=2)
    table.alignment = WD_TABLE_ALIGNMENT.CENTER
    table.autofit = False
    for key, value in rows:
        cells = table.add_row().cells
        cells[0].width = Inches(widths[0])
        cells[1].width = Inches(widths[1])
        set_cell_shading(cells[0], PALE_BLUE)
        for cell in cells:
            set_cell_margins(cell)
            cell.vertical_alignment = WD_CELL_VERTICAL_ALIGNMENT.CENTER
        r = cells[0].paragraphs[0].add_run(key)
        set_run_font(r, 10, True, DARK_BLUE)
        r = cells[1].paragraphs[0].add_run(value)
        set_run_font(r, 10)


def style_document(doc: Document) -> None:
    section = doc.sections[0]
    section.page_width = Inches(8.5)
    section.page_height = Inches(11)
    section.top_margin = Inches(0.78)
    section.bottom_margin = Inches(0.72)
    section.left_margin = Inches(0.82)
    section.right_margin = Inches(0.82)

    normal = doc.styles["Normal"]
    normal.font.name = "Calibri"
    normal._element.rPr.rFonts.set(qn("w:eastAsia"), "Microsoft YaHei")
    normal.font.size = Pt(10.5)
    normal.font.color.rgb = TEXT
    normal.paragraph_format.space_after = Pt(5)
    normal.paragraph_format.line_spacing = 1.2

    settings = {
        "Title": (26, "000000", 0, 8),
        "Subtitle": (12, "000000", 0, 14),
        "Heading 1": (16, "000000", 16, 7),
        "Heading 2": (13, "000000", 12, 6),
        "Heading 3": (11.5, "000000", 9, 4),
    }
    for name, (size, color, before, after) in settings.items():
        style = doc.styles[name]
        style.font.name = "Calibri"
        style._element.rPr.rFonts.set(qn("w:eastAsia"), "Microsoft YaHei")
        style.font.size = Pt(size)
        style.font.color.rgb = RGBColor.from_string(color)
        style.font.bold = name != "Subtitle"
        style.paragraph_format.space_before = Pt(before)
        style.paragraph_format.space_after = Pt(after)
        style.paragraph_format.keep_with_next = True
        p_pr = style._element.get_or_add_pPr()
        border = p_pr.find(qn("w:pBdr"))
        if border is not None:
            p_pr.remove(border)

    for name in ("List Bullet", "List Number"):
        style = doc.styles[name]
        style.font.name = "Calibri"
        style._element.rPr.rFonts.set(qn("w:eastAsia"), "Microsoft YaHei")
        style.font.size = Pt(10.5)
        style.paragraph_format.left_indent = Inches(0.38)
        style.paragraph_format.first_line_indent = Inches(-0.19)
        style.paragraph_format.space_after = Pt(3)
        style.paragraph_format.line_spacing = 1.18


def add_header_footer(doc: Document) -> None:
    section = doc.sections[0]
    section.different_first_page_header_footer = True

    first_header = section.first_page_header
    p = first_header.paragraphs[0]
    p.alignment = WD_ALIGN_PARAGRAPH.RIGHT
    r = p.add_run("客户资料 · 请妥善保管")
    set_run_font(r, 8.5, False, "7A8793")

    header = section.header
    p = header.paragraphs[0]
    p.alignment = WD_ALIGN_PARAGRAPH.LEFT
    p.paragraph_format.space_after = Pt(2)
    r = p.add_run("病程日历 1.3.0  |  客户使用指南")
    set_run_font(r, 8.5, False, "000000")

    for footer in (section.footer, section.first_page_footer):
        p = footer.paragraphs[0]
        p.alignment = WD_ALIGN_PARAGRAPH.CENTER
        p.paragraph_format.space_before = Pt(2)
        add_page_field(p)
        for run in p.runs:
            set_run_font(run, 8.5, False, "7A8793")


def build() -> None:
    doc = Document()
    style_document(doc)
    add_header_footer(doc)

    p = doc.add_paragraph(style="Title")
    p.add_run("病程日历使用指南")
    p = doc.add_paragraph(style="Subtitle")
    p.add_run("个人病情记录、复查提醒与用药管理")

    add_kv_table(
        doc,
        [
            ("适用版本", "1.3.0"),
            ("适用设备", "Android 8.0 及以上的华为、荣耀、Redmi/小米及其他 Android 手机"),
            ("不支持", "HarmonyOS NEXT 原生系统"),
            ("数据方式", "完全离线，本地加密存储"),
        ],
    )
    doc.add_paragraph()
    add_callout(
        doc,
        "使用前请先阅读",
        "数据只保存在手机本地。卸载应用、清除应用数据或手机损坏，都可能导致本机数据消失。请定期导出加密备份，并把备份文件保存到另一台设备、U 盘或电脑。已安装旧版本时，请直接覆盖安装新版，不要先卸载。",
    )

    add_heading(doc, "1. 软件用途", 1)
    add_body(doc, "“病程日历”用于个人病情资料整理和提醒，可帮助您：")
    add_bullets(
        doc,
        [
            "为本人和家人分别建立档案，并在各页面切换当前成员。",
            "按日历查看每次就诊、病情变化、诊断、治疗和备注。",
            "使用快速录入整理单条或多日期病程，并在保存前逐条核对。",
            "保存每次检查报告的多张图片和多个 PDF。",
            "设置定期复查提醒，例如“每 3 个月一次”或“每周四”。",
            "登记药物疗程、每日服药时间，并记录“已服”或“跳过”。",
            "使用系统指纹、面容或锁屏密码保护应用。",
            "导出全家庭密码加密备份，在换机或重新安装后恢复。",
            "检查通知、闹钟、电池优化、最近备份和数据库/附件完整性。",
            "误删病历、报告、复查或药物后，可在 30 天回收站恢复。",
        ],
    )
    add_callout(doc, "医疗提示", "本应用只用于记录和提醒，不能替代医生诊断、处方或用药指导。", PALE_GREEN)

    add_heading(doc, "2. 安装应用", 1)
    add_steps(
        doc,
        [
            "从 GitHub Releases 下载“HealthTimeline-1.3.0.apk”并保存到手机。",
            "在“文件管理”中点击 APK。",
            "首次安装时，按系统提示允许当前文件管理器“安装未知应用”。",
            "安装完成后，可关闭“安装未知应用”权限。",
            "打开“病程日历”，按提示允许通知。",
        ],
    )
    add_body(doc, "如果提示“无法安装”，请确认手机为 Android 8.0 或以上、APK 文件下载完整，并且不是 HarmonyOS NEXT 原生系统。")

    add_heading(doc, "3. 首次使用必须检查的权限", 1)
    add_body(doc, "为了让复查和服药提醒尽量准时，请完成以下设置：")
    add_steps(
        doc,
        [
            "通知权限：允许“病程日历”发送通知。",
            "精确闹钟：在“设置 → 安全与提醒检查”按提示允许精确提醒；拒绝后仍可提醒，但可能延迟。",
            "后台运行：允许应用后台活动和自动启动。",
            "电池管理：将应用设为“不限制”或“不优化”。",
        ],
    )
    add_heading(doc, "华为/荣耀", 2)
    add_body(doc, "通常可在“设置 → 应用和服务 → 应用启动管理”中找到本应用，关闭自动管理后允许“自动启动、关联启动、后台活动”。")
    add_heading(doc, "Redmi/小米", 2)
    add_body(doc, "通常可在“设置 → 应用设置 → 应用管理 → 病程日历”中打开“自启动”，并在“省电策略”中选择“无限制”。不同机型和系统版本的菜单名称可能略有不同。")

    doc.add_page_break()
    add_heading(doc, "4. 四个主要页面", 1)
    table = doc.add_table(rows=1, cols=2)
    table.alignment = WD_TABLE_ALIGNMENT.CENTER
    table.autofit = False
    table.columns[0].width = Inches(1.25)
    table.columns[1].width = Inches(5.1)
    hdr = table.rows[0].cells
    hdr[0].width = Inches(1.25)
    hdr[1].width = Inches(5.1)
    hdr[0].text = "页面"
    hdr[1].text = "用途"
    set_repeat_table_header(table.rows[0])
    for cell in hdr:
        set_cell_shading(cell, PALE_BLUE)
        set_cell_margins(cell)
        for run in cell.paragraphs[0].runs:
            set_run_font(run, 10, True, DARK_BLUE)
    for name, purpose in [
        ("日历", "切换家庭成员；查看、搜索、新增和修改病历"),
        ("复查", "切换成员；设置一次性或周期性复查，处理待复查事项"),
        ("用药", "切换成员；登记药物、疗程和每日服药记录"),
        ("设置", "管理家庭档案和应用锁，运行安全检查，导出或恢复全家庭备份"),
    ]:
        cells = table.add_row().cells
        cells[0].width = Inches(1.25)
        cells[1].width = Inches(5.1)
        cells[0].text = name
        cells[1].text = purpose
        for cell in cells:
            set_cell_margins(cell)
            for run in cell.paragraphs[0].runs:
                set_run_font(run, 10)

    add_heading(doc, "5. 新增病情或就诊记录", 1)
    add_steps(
        doc,
        [
            "打开“日历”，切换到需要记录的月份。",
            "选择日期，点击新增按钮。",
            "选择或新建病情分类，例如“乳腺复查”或“上颌窦炎术后”。",
            "填写标题，并按需要填写就诊阶段、症状、诊断、治疗、用药、医院、医生和备注。",
            "点击“保存”。",
        ],
    )
    add_body(doc, "日历中每个日期最多显示两个标题；当天记录更多时会显示“+N”。点击日期即可查看全部记录。已有历史记录引用的病情分类只能归档，不能直接删除。")
    add_heading(doc, "快速录入", 2)
    add_body(doc, "新增病历时可展开“快速录入”。简单内容可直接在手机本地解析；复杂长文或跨多日内容，可先复制应用提供的 AI 整理提示词，自行选择外部 AI，删除姓名、身份证号、电话等敏感信息后再整理。应用不会自动发送病历，也不申请网络权限。")
    add_steps(
        doc,
        [
            "粘贴原始文字，或粘贴外部 AI 按模板整理后的结果。",
            "点击解析，逐条展开核对日期、标题、病名、医生、诊断、治疗、用药和备注。",
            "确认标题以病名开头，并确认备注中的“原文记录”完整且顺序正确。",
            "删除不需要的草稿或修改错误内容，最后点击批量保存。",
        ],
    )
    add_callout(doc, "必须核对", "快速录入只帮助整理资料，不提供诊断或治疗建议。保存前请逐条核对；关闭页面或取消时不会写入正式病历。", PALE_YELLOW)

    add_heading(doc, "6. 保存和查看检查报告", 1)
    add_steps(
        doc,
        [
            "打开一条病历记录。",
            "在附件区域选择拍照、选择图片或选择 PDF。",
            "选中的文件会复制到应用的私有存储中，原文件移动或改名不影响应用内副本。",
            "保存后，点击图片可缩放查看；点击 PDF 可逐页查看。",
        ],
    )
    add_callout(doc, "附件限制", "每条病历可保存多张图片和多个 PDF。单个文件不能超过 100 MB。文件损坏、格式不支持或手机空间不足时，应用会取消本次导入，不会保留半份附件。", PALE_BLUE)
    add_body(doc, "删除单个报告时，点击附件旁或查看页中的删除按钮，核对文件名并再次确认。只删除所选图片或 PDF，不会删除病历；删除后先进入 30 天回收站。")

    add_heading(doc, "7. 设置复查提醒", 1)
    add_steps(
        doc,
        [
            "打开“复查”，点击新增。",
            "填写复查名称、首次日期、提醒时间和提前天数；新建计划默认提前 3 天，可改为 0、1、3 或 7 天。",
            "选择重复方式：一次性、每 N 天、每 N 周指定星期，或每 N 月指定日期。",
            "保存后，系统会安排下一次提醒。",
            "完成复查后标记“已完成”；不需要本次复查时可标记“已跳过”。",
        ],
    )
    add_heading(doc, "计算示例", 2)
    add_bullets(
        doc,
        [
            "7 月 9 日设置“每 3 个月”，下一次为 10 月 9 日。",
            "设置“每周四”，应用会自动跨月、跨年计算下一个周四。",
            "1 月 31 日设置每月提醒，2 月使用当月最后一天，3 月仍回到 31 日。",
        ],
    )
    add_body(doc, "未处理的复查会继续显示为待办或逾期。只有标记“已完成”或“已跳过”后，周期提醒才推进到下一次。")

    add_heading(doc, "8. 登记用药和每日打卡", 1)
    add_steps(
        doc,
        [
            "打开“用药”，点击新增药物。",
            "填写药名、剂量、单位、开始日期、结束日期和服用说明。",
            "选择“按需服用”，或设置每天一个或多个服药时间，例如 08:00、20:00。",
            "在用药月历中选择日期，再在下方切换“当日用药、用药疗程、已结束用药”查看相应信息。",
            "今天或过去日期可补记“已服”或“跳过”，实际时间使用每 5 分钟一档的时间选择器；未来日期只读。",
            "已服与跳过可以在二次确认后互相修正，也可修改实际服用时间。",
            "修改药物时间、剂量或计划模式时选择生效日期；过去的计划和打卡继续保留当时的时间与剂量。",
        ],
    )
    add_body(doc, "未点击“已服”或“跳过”的项目保持“未记录”，应用不会自动认定为漏服。点击“结束疗程”会记录结束日期和时间并停止后续提醒，当天及此前历史记录仍然保留；如果误点，可在“已结束用药”点击“恢复疗程”，恢复原计划和后续提醒。")

    add_heading(doc, "9. 安全与提醒检查", 1)
    add_steps(
        doc,
        [
            "打开“设置 → 安全与提醒检查”。",
            "确认通知总开关、复查/用药提醒类别、精确闹钟和电池优化状态。",
            "点击“发送 10 秒测试提醒”，确认手机可以收到通知。",
            "点击“检查数据库和全部附件”，等待检查完成。",
            "需要技术协助时导出本地诊断报告；报告不包含医疗正文、姓名、药名、附件名称或路径。",
        ],
    )
    add_body(doc, "华为、荣耀和 Redmi 还有厂商自己的自启动及后台开关，Android 无法自动读取，请按本指南前面的品牌说明人工确认。")

    add_heading(doc, "回收站", 2)
    add_body(doc, "误删病历、单个检查报告、复查计划或药物后，打开“设置 → 回收站”，确认当前家庭成员并点击“恢复”。资料保留 30 天；到期会自动清理。“永久删除”不可撤销，操作前请先导出备份。")

    add_heading(doc, "10. 导出加密备份", 1)
    add_callout(doc, "建议频率", "至少每周备份一次，并在添加重要检查报告、换手机或更新应用前额外备份。", PALE_GREEN)
    add_steps(
        doc,
        [
            "打开“设置 → 导出加密备份”。",
            "输入至少 8 位密码并再次确认。",
            "选择保存位置，生成 .htbackup 文件。",
            "将备份文件复制到电脑、U 盘或另一台可靠设备。",
            "备份文件和密码请分开保管。",
        ],
    )
    add_callout(doc, "密码无法找回", "v5 备份包含全部家庭成员的病历、复查、历史用药计划、用药记录、附件和回收站。密码不会保存在应用中，忘记后无法恢复该备份。", PALE_YELLOW)

    add_heading(doc, "11. 恢复备份或更换手机", 1)
    add_steps(
        doc,
        [
            "在新手机安装“病程日历”。",
            "将 .htbackup 备份文件复制到新手机。",
            "打开“设置 → 从备份恢复”，选择备份文件。",
            "输入导出时设置的密码并确认。",
            "应用会先检查密码、版本、成员引用、文件完整性和可用空间，再显示安全合并或整体替换选项。",
        ],
    )
    add_callout(doc, "整体替换前先备份", "整体替换会删除本机独有资料，因此应用会先强制导出当前数据安全备份。导出或验证失败时，现有数据不会被修改。", PALE_BLUE)

    add_heading(doc, "12. 更新应用", 1)
    add_body(doc, "如果手机已经安装旧版本，请从 GitHub 下载新版 APK 后直接覆盖安装。不要先卸载旧版，也不要清除应用数据。1.3.0 使用正式数据库迁移保留旧资料并增加回收站；如果系统提示签名不一致，请停止操作并联系软件提供方。")

    add_heading(doc, "13. 常见问题", 1)
    questions = [
        ("收不到提醒或提醒延迟", "先进入“安全与提醒检查”查看状态并发送测试提醒，再检查自动启动、后台活动和电池“不限制”设置；需要协助时可导出不含医疗正文的诊断报告。"),
        ("Redmi/小米手机锁屏后不提醒", "打开本应用的“自启动”，将省电策略设为“无限制”，并确认通知允许显示。HyperOS/MIUI 更新后，建议再次检查这些设置。"),
        ("华为/荣耀手机锁屏后不提醒", "在“应用启动管理”中改为手动管理，并允许自动启动、关联启动和后台活动；同时关闭对本应用的电池优化。"),
        ("报告无法导入", "请确认文件是常见图片或 PDF、单个文件不超过 100 MB、文件没有损坏，并且手机剩余空间充足。"),
        ("忘记备份密码", "加密备份密码无法找回。若旧手机中的应用数据仍在，请重新导出备份并设置新密码。"),
        ("卸载后记录不见了", "卸载应用会删除其私有数据。重新安装后只能通过之前导出的 .htbackup 文件恢复。"),
    ]
    for question, answer in questions:
        add_heading(doc, question, 2)
        add_body(doc, answer)

    add_heading(doc, "14. 隐私与安全说明", 1)
    add_bullets(
        doc,
        [
            "应用不申请网络权限，不登录账号，不上传病历，也不进行云同步。",
            "数据库经过加密，图片和 PDF 保存在应用私有目录。",
            "可开启系统身份验证；锁屏通知隐藏成员姓名、病情和药名。",
            "手机遗失、损坏、恢复出厂设置、卸载或清除数据仍可能造成数据丢失；离线应用无法替您找回未备份的数据。",
        ],
    )

    add_heading(doc, "15. 交付客户前快速检查", 1)
    add_steps(
        doc,
        [
            "能正常打开应用并新增一条测试病历。",
            "已允许通知、精确闹钟和后台运行。",
            "已在安全检查中心发送 10 秒测试提醒并确认能够收到。",
            "数据库和全部附件完整性检查通过。",
            "已成功导出一次加密备份，并确认记得密码。",
            "已把备份复制到手机之外的安全位置。",
        ],
    )
    add_callout(doc, "需要技术支持时", "请联系软件提供方，并说明问题步骤；可同时提供安全检查中心导出的本地诊断报告。", PALE_GREEN)

    core_props = doc.core_properties
    core_props.title = "病程日历使用指南"
    core_props.subject = "病程日历 Android 应用客户使用说明"
    core_props.author = "病程日历"
    core_props.keywords = "病程日历, Android, 使用指南, 复查提醒, 用药记录, 加密备份"

    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    doc.save(OUTPUT)
    print(OUTPUT)


if __name__ == "__main__":
    build()
