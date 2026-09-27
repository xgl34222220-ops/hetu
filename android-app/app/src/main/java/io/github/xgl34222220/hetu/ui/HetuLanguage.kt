package io.github.xgl34222220.hetu.ui

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalConfiguration

val LocalHetuLanguage = staticCompositionLocalOf { "system" }

/** Stable UI vocabulary. Technical diagnostics and user content retain their original text. */
private val vocabulary = """
首页|Home|首頁|Главная
面板|Panel|面板|Панель
策略|Proxies|策略|Прокси
工具|Tools|工具|Инструменты
设置|Settings|設定|Настройки
语言|Language|語言|Язык
跟随系统|System default|跟隨系統|Как в системе
概览|Overview|概覽|Обзор
连接|Connections|連線|Соединения
规则|Rules|規則|Правила
订阅|Subscriptions|訂閱|Подписки
日志|Logs|日誌|Журнал
启动|Start|啟動|Запустить
停止|Stop|停止|Остановить
重启|Restart|重新啟動|Перезапустить
重载|Reload|重新載入|Перезагрузить
保存|Save|儲存|Сохранить
取消|Cancel|取消|Отмена
关闭|Close|關閉|Закрыть
打开|Open|開啟|Открыть
删除|Delete|刪除|Удалить
编辑|Edit|編輯|Изменить
添加|Add|新增|Добавить
复制|Copy|複製|Копировать
搜索|Search|搜尋|Поиск
返回|Back|返回|Назад
刷新|Refresh|重新整理|Обновить
更新|Update|更新|Обновить
导入|Import|匯入|Импорт
导出|Export|匯出|Экспорт
名称|Name|名稱|Название
文件管理|Files|檔案管理|Файлы
运行文件编辑|File editor|執行檔案編輯|Редактор файлов
主题与界面|Appearance|主題與介面|Оформление
主题|Theme|主題|Тема
通知设置|Notifications|通知設定|Уведомления
Web 面板|Web panels|Web 面板|Веб-панели
策略显示|Proxy display|策略顯示|Вид прокси
基础代理配置|Proxy configuration|基本代理設定|Настройка прокси
其他代理配置|Advanced configuration|其他代理設定|Дополнительно
延迟目标|Latency targets|延遲目標|Проверка задержки
策略图标|Proxy icons|策略圖示|Значки прокси
备份与恢复|Backup and restore|備份與還原|Резервное копирование
关于河图|About Hetu|關於河圖|О Hetu
关于|About|關於|О приложении
开源库|Open source libraries|開源程式庫|Открытые библиотеки
赞助与支持|Support|贊助與支援|Поддержка
订阅与配置|Subscriptions and configurations|訂閱與設定|Подписки и конфигурации
网络匹配|Network automation|網路比對|Автоматизация сети
共享网络|Shared network|共用網路|Общая сеть
绕过规则|Bypass rules|繞過規則|Правила обхода
应用分流|App routing|應用程式分流|Маршрутизация приложений
代理应用名单|App selection|代理應用程式清單|Выбор приложений
运行核心|Runtime core|執行核心|Ядро
内核管理|Core management|核心管理|Управление ядром
启动配置|Startup configuration|啟動設定|Конфигурация запуска
脚本|Scripts|指令碼|Скрипты
日志查看|Log viewer|日誌檢視|Просмотр журнала
自动刷新|Auto refresh|自動重新整理|Автообновление
逐条卡片|Card view|逐條卡片|Карточки
全部|All|全部|Все
全选|Select all|全選|Выбрать все
清空|Clear|清空|Очистить
重命名|Rename|重新命名|Переименовать
新建文件|New file|新增檔案|Новый файл
新建文件夹|New folder|新增資料夾|Новая папка
下载文件|Download file|下載檔案|Скачать файл
语法校验|Validate syntax|語法檢查|Проверить синтаксис
继续编辑|Continue editing|繼續編輯|Продолжить
放弃修改|Discard changes|放棄修改|Отменить изменения
下拉刷新|Pull to refresh|下拉重新整理|Потяните для обновления
松开刷新|Release to refresh|放開重新整理|Отпустите для обновления
正在刷新|Refreshing|正在重新整理|Обновление
刷新完成|Refreshed|重新整理完成|Обновлено
健康检查|Health checks|健康檢查|Проверка доступности
基础与运行|Runtime|基本與執行|Работа приложения
外观|Appearance|外觀|Оформление
交互|Interaction|互動|Взаимодействие
玻璃与导航|Glass and navigation|玻璃與導覽|Стекло и навигация
界面缩放|Interface scale|介面縮放|Масштаб интерфейса
悬浮底栏|Floating navigation|浮動底列|Плавающая навигация
底栏液态玻璃|Liquid glass navigation|底列液態玻璃|Жидкое стекло
预测式返回动画|Predictive back|預測式返回動畫|Анимация возврата
顶栏模糊样式|Header blur style|頂列模糊樣式|Размытие заголовка
高斯模糊|Gaussian blur|高斯模糊|Гауссово размытие
渐进式模糊|Progressive blur|漸進式模糊|Постепенное размытие
""".trimIndent().lineSequence().map { it.split('|') }.associate { it[0] to it.drop(1) }

fun translateHetuText(source: String, language: String): String {
    val index = when { language.startsWith("en") -> 0; language.startsWith("zh-TW") || language.startsWith("zh-HK") || language.contains("Hant") -> 1; language.startsWith("ru") -> 2; else -> return source }
    return vocabulary[source]?.get(index) ?: source
}

@Composable fun ht(source: String): String {
    val setting = LocalHetuLanguage.current
    val language = if (setting == "system") LocalConfiguration.current.locales[0].toLanguageTag() else setting
    return translateHetuText(source, language)
}
