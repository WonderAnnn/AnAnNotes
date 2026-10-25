package com.anan.anannotes.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.anan.anannotes.ui.components.CompactPageHeader
import com.anan.anannotes.ui.components.PageScaffold

@Composable
fun PrivacyNoticeScreen(onBack: () -> Unit) {
  PageScaffold(modifier = Modifier.testTag("privacy-notice-screen")) {
    Column(
      modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
        .padding(horizontal = 20.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      CompactPageHeader(title = "隐私说明", onBack = onBack, testTag = "privacy-notice-header")
      Text(
        "安安简记以本机记录为主。下面说明本版本的数据保存方式，以及使用系统导入、导出功能时需要留意的边界。",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 4.dp),
      )

      PrivacySection(
        title = "联网与账号",
        body = "本应用不申请互联网权限，不要求注册账号，也不提供笔记云同步。发布包会在构建时检查合并后的权限清单，发现 INTERNET 权限即阻止打包。",
        testTag = "privacy-network-section",
      )
      PrivacySection(
        title = "普通笔记",
        body = "普通笔记保存在应用私有目录中的本地数据库和文件中，由 Android 应用沙箱隔离；普通笔记未做应用级加密。请保护好设备解锁凭据，并在借出设备前评估本地数据风险。",
      )
      PrivacySection(
        title = "私密笔记",
        body = "私密模块使用独立的加密存储，只有通过模块验证后才能在应用内查看。导出到系统文件夹、分享或复制到剪贴板后，内容会离开私密模块的保护范围。",
      )
      PrivacySection(
        title = "图片识别",
        body = "OCR 使用随应用提供的离线识别组件和本地模型处理图片；识别流程不需要把图片发送到服务器。识别准确性仍可能受图片质量、排版和语言影响。",
      )
      PrivacySection(
        title = "导入、导出与系统服务",
        body = "导入和导出由你主动选择文件或位置。应用只访问系统选择器授予的内容；文件提供商可能会同步到云端，安安简记无法控制其处理方式。导出的文件是普通文件，可能被其他应用读取。",
      )
      PrivacySection(
        title = "备份与剪贴板",
        body = "Android 系统应用备份已关闭；手动备份由你发起并使用备份密码加密。请分别妥善保管备份文件和密码。复制笔记会把文字交给系统剪贴板，其他应用或键盘可能按系统规则读取剪贴板内容。",
      )
      PrivacySection(
        title = "可核验信息",
        body = "发布包随附权限清单、依赖清单、APK 的 SHA-256 和断网测试记录，方便自行检查或交由第三方审查。这些是工程验证材料，不等于独立安全审计，也不能证明设备或系统本身没有风险。",
      )
    }
  }
}

@Composable
private fun PrivacySection(title: String, body: String, testTag: String? = null) {
  Card(
    modifier = Modifier.fillMaxWidth().then(
      if (testTag == null) Modifier else Modifier.testTag(testTag),
    ),
    shape = RoundedCornerShape(18.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
      Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}
