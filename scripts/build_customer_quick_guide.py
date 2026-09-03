from pathlib import Path
import importlib.util


ROOT = Path(__file__).resolve().parents[1]
BASE_PATH = ROOT / "scripts" / "build_customer_guide.py"
SPEC = importlib.util.spec_from_file_location("customer_guide_base", BASE_PATH)
BASE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BASE)


def build():
    doc = BASE.Document()
    BASE.style_document(doc)
    BASE.add_header_footer(doc)

    p = doc.add_paragraph(style="Title")
    p.add_run("病程日历简明使用指南")
    p = doc.add_paragraph(style="Subtitle")
    p.add_run("主要功能与操作方法")

    BASE.add_kv_table(doc, [
        ("适用版本", "1.0.2"),
        ("适用手机", "Android 8.0 及以上的华为、荣耀、Redmi/小米等 Android 手机"),
        ("不支持", "HarmonyOS NEXT 原生系统"),
    ])
    doc.add_paragraph()
    BASE.add_callout(doc, "重要提醒", "数据只保存在手机本地。卸载应用或清除应用数据前，必须先导出加密备份。")

    BASE.add_heading(doc, "一、主要功能", 1)
    table = doc.add_table(rows=1, cols=2)
    table.alignment = BASE.WD_TABLE_ALIGNMENT.CENTER
    table.autofit = False
    table.columns[0].width = BASE.Inches(1.35)
    table.columns[1].width = BASE.Inches(5.0)
    hdr = table.rows[0].cells
    hdr[0].text = "功能"
    hdr[1].text = "说明"
    BASE.set_repeat_table_header(table.rows[0])
    for cell in hdr:
        BASE.set_cell_shading(cell, BASE.PALE_BLUE)
        BASE.set_cell_margins(cell)
        for run in cell.paragraphs[0].runs:
            BASE.set_run_font(run, 10, True, BASE.DARK_BLUE)
    for name, purpose in [
        ("病程日历", "按日期记录病情、诊断、治疗、用药、医院、医生和备注"),
        ("复查提醒", "支持一次性、每 N 天、每周指定星期、每 N 个月提醒"),
        ("用药记录", "登记药名、剂量、疗程和每日服药时间，记录已服或跳过"),
        ("检查报告", "每条病历可保存多张图片和多个 PDF"),
        ("加密备份", "导出完整备份，换手机或重新安装后可恢复"),
    ]:
        cells = table.add_row().cells
        cells[0].text = name
        cells[1].text = purpose
        for cell in cells:
            BASE.set_cell_margins(cell)
            for run in cell.paragraphs[0].runs:
                BASE.set_run_font(run, 10)

    BASE.add_heading(doc, "二、安装和首次设置", 1)
    BASE.add_steps(doc, [
        "在手机“文件管理”中点击“病程日历-1.0.2-release.apk”。",
        "按系统提示允许“安装未知应用”，完成安装。",
        "首次打开时允许通知权限。",
        "在应用“设置”页允许精确闹钟。",
        "允许病程日历自动启动、后台运行，并把电池策略设为“不限制”。",
    ])
    BASE.add_callout(doc, "不同品牌的设置位置", "华为/荣耀一般在“应用启动管理”中设置；Redmi/小米一般在应用管理中打开“自启动”，并将省电策略设为“无限制”。", BASE.PALE_BLUE)

    BASE.add_heading(doc, "三、怎么使用", 1)

    BASE.add_heading(doc, "1. 新增病情或就诊记录", 2)
    BASE.add_steps(doc, [
        "打开“日历”，选择日期。",
        "点击新增，选择或新建病情分类。",
        "填写标题、症状、诊断、治疗、用药等内容。",
        "点击“保存”。",
    ])
    BASE.add_body(doc, "点击日历中的日期可以查看当天全部记录。日期下面最多显示两个标题，更多记录会显示“+N”。")
    BASE.add_body(doc, "在日历顶部输入标题、诊断、治疗、用药等关键字，可以搜索全部历史记录；点击搜索结果会跳到对应日期并打开记录。")

    BASE.add_heading(doc, "2. 保存检查报告", 2)
    BASE.add_steps(doc, [
        "打开一条病历记录。",
        "在附件区域选择拍照、图片或 PDF。",
        "保存后，点击附件即可查看。",
    ])
    BASE.add_body(doc, "每条病历可保存多张图片和多个 PDF，单个文件不能超过 100 MB。")

    BASE.add_heading(doc, "3. 设置复查提醒", 2)
    BASE.add_steps(doc, [
        "打开“复查”，点击新增。",
        "填写复查名称、首次日期、提醒时间和提前天数。",
        "选择重复方式并保存。",
        "复查后标记“已完成”；本次不需要时标记“已跳过”。",
    ])
    BASE.add_callout(doc, "示例", "7 月 9 日设置“每 3 个月”，下一次为 10 月 9 日；设置“每周四”，应用会自动计算下一个周四。", BASE.PALE_GREEN)

    BASE.add_heading(doc, "4. 登记用药和打卡", 2)
    BASE.add_steps(doc, [
        "打开“用药”，点击新增药物。",
        "填写药名、剂量、开始和结束日期。",
        "设置每天一个或多个服药时间，或选择“按需服用”。",
        "在“今日用药”或通知中标记“已服”；不服本次可标记“跳过”。",
    ])
    BASE.add_body(doc, "未操作的项目保持“未记录”，不会自动认定为漏服。")

    BASE.add_heading(doc, "5. 导出和恢复备份", 2)
    BASE.add_body(doc, "导出：打开“设置 → 导出加密备份”，设置至少 8 位密码，选择保存位置。")
    BASE.add_body(doc, "恢复：打开“设置 → 从备份恢复”，选择 .htbackup 文件并输入原密码。")
    BASE.add_callout(doc, "备份建议", "建议每周备份一次，并把备份复制到电脑、U 盘或另一台设备。备份密码无法找回，请妥善保管。", BASE.PALE_YELLOW)

    BASE.add_heading(doc, "四、必须注意", 1)
    BASE.add_bullets(doc, [
        "本应用只用于记录和提醒，不能替代医生诊断或用药指导。",
        "应用完全离线，不上传病历，也不进行云同步。",
        "卸载、清除应用数据或手机损坏会导致本机数据消失；请定期备份。",
        "更新应用时直接覆盖安装新版，不要先卸载旧版。",
        "收不到提醒时，检查通知、精确闹钟、自动启动、后台运行和电池“不限制”设置。",
        "换手机时，先在旧手机导出备份，再在新手机安装应用并恢复。",
    ])
    BASE.add_callout(doc, "需要技术支持时", "请提供手机品牌、型号、系统版本和问题出现的具体步骤。", BASE.PALE_GREEN)

    props = doc.core_properties
    props.title = "病程日历简明使用指南"
    props.subject = "病程日历主要功能与操作方法"
    props.author = "病程日历"
    BASE.OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    doc.save(BASE.OUTPUT)
    print(BASE.OUTPUT)


if __name__ == "__main__":
    build()
