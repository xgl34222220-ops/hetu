package io.github.xgl34222220.hetu.ui

import androidx.compose.runtime.*
import android.content.SharedPreferences
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

val LocalHetuLanguage = staticCompositionLocalOf { "system" }

/** Observe the persisted setting, including changes made by a different settings screen. */
@Composable
fun rememberHetuLanguage(): String {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("hetu", 0) }
    fun currentSetting() = prefs.getString("appLanguage", "system").orEmpty().ifBlank { "system" }
    var language by remember(prefs) { mutableStateOf(currentSetting()) }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "appLanguage" || key == null) language = currentSetting()
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        language = currentSetting()
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return language
}

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
规则集|Rule sets|規則集|Наборы правил
高级代理配置|Advanced proxy settings|進階代理設定|Дополнительные настройки прокси
语言与主题|Language and appearance|語言與主題|Язык и оформление
主题设置|Appearance settings|主題設定|Настройки оформления
默认面板|Default panel|預設面板|Панель по умолчанию
默认面板页面|Default panel page|預設面板頁面|Начальная страница панели
开机启动与下载|Startup and downloads|開機啟動與下載|Автозапуск и загрузки
核心、模式与当前配置|Core, mode and current configuration|核心、模式與目前設定|Ядро, режим и текущая конфигурация
性能、DNS 与资源限制|Performance, DNS and resource limits|效能、DNS 與資源限制|Производительность, DNS и лимиты ресурсов
显示语言、主题与显示|Language, theme and display|顯示語言、主題與顯示|Язык, тема и экран
选择面板与显示偏好|Panel and display preferences|選擇面板與顯示偏好|Панель и параметры отображения
导出与恢复应用设置|Export and restore app settings|匯出與還原應用程式設定|Экспорт и восстановление настроек
启动设置与资源下载|Startup settings and resource downloads|啟動設定與資源下載|Автозапуск и загрузка ресурсов
管理运行状态提醒|Manage runtime notifications|管理執行狀態提醒|Уведомления о состоянии
版本与开源信息|Version and open source information|版本與開源資訊|Версия и открытый исходный код
切换应用语言|Choose the app language|切換應用程式語言|Выбрать язык приложения
主题模式|Theme mode|主題模式|Режим темы
浅色模式|Light mode|淺色模式|Светлая тема
深色模式|Dark mode|深色模式|Тёмная тема
浅色|Light|淺色|Светлая
深色|Dark|深色|Тёмная
与系统设置保持一致|Use system settings|與系統設定保持一致|Как в системе
始终使用浅色主题|Always use a light theme|一律使用淺色主題|Всегда светлая тема
始终使用深色主题|Always use a dark theme|一律使用深色主題|Всегда тёмная тема
Monet 动态取色|Dynamic colors|Monet 動態取色|Динамические цвета
使用系统壁纸提供的配色|Use colors from the system wallpaper|使用系統桌布提供的配色|Цвета из системных обоев
深色纯黑背景|Pure black dark background|深色純黑背景|Чёрный фон в тёмной теме
OLED 模式使用纯黑画布|Use a pure black canvas in OLED mode|OLED 模式使用純黑畫布|Чёрный фон для OLED
强调色|Accent color|強調色|Акцентный цвет
模糊效果|Blur effects|模糊效果|Размытие
控制顶栏、底栏与浮层的实时模糊|Blur headers, navigation and overlays|控制頂列、底列與浮層的即時模糊|Размытие панелей и всплывающих окон
选择顶栏磨砂的过渡方式|Choose the header blur transition|選擇頂列磨砂的過渡方式|Выбрать переход размытия заголовка
关闭后底栏吸附屏幕底部|Dock navigation at the bottom when disabled|關閉後底列吸附螢幕底部|Закрепить навигацию внизу при отключении
为底栏加入通透的折射与高光|Add translucent refraction and highlights|為底列加入通透的折射與高光|Преломление и блики для навигации
预测性返回动画|Predictive back animation|預測式返回動畫|Анимация возврата
返回手势让页面跟手缩放、位移并露出上一层|Preview the previous page during the back gesture|返回手勢讓頁面跟手縮放、位移並露出上一層|Предпросмотр предыдущей страницы при возврате
动画方向跟随滑动边缘|Follow the gesture edge|動畫方向跟隨滑動邊緣|Следовать краю жеста
从右边缘返回时方向同步反转|Reverse the animation for right-edge gestures|從右邊緣返回時方向同步反轉|Обратить анимацию для жеста справа
统一调整界面和文字大小|Adjust interface and text size together|統一調整介面和文字大小|Масштаб интерфейса и текста
顶部更浓，向内容区域逐渐消散|Stronger at the top, fading into the content|頂部更濃，向內容區域逐漸消散|Сильнее вверху, слабее у содержимого
整条顶栏使用均匀磨砂|Apply uniform blur across the header|整條頂列使用均勻磨砂|Равномерное размытие заголовка
启动时打开面板|Open the panel on startup|啟動時開啟面板|Открывать панель при запуске
显示底栏面板入口|Show the panel in navigation|顯示底列面板入口|Показывать панель в навигации
加速下载|Download acceleration|加速下載|Ускорение загрузок
镜像前缀|Mirror prefix|鏡像前綴|Префикс зеркала
请填写 http/https 地址|Enter an http/https URL|請填寫 http/https 位址|Введите адрес http/https
回到顶部|Back to top|回到頂部|Наверх
清除|Clear|清除|Очистить
关闭详情|Close details|關閉詳情|Закрыть подробности
正在启动|Starting|正在啟動|Запуск
正在停止|Stopping|正在停止|Остановка
正在重启|Restarting|正在重新啟動|Перезапуск
正在重载|Reloading|正在重新載入|Перезагрузка
运行中|Running|執行中|Работает
未运行|Stopped|未執行|Остановлено
尚未启动代理|Proxy has not started|尚未啟動代理|Прокси не запущен
常用|Favorites|常用|Основное
配置与订阅|Configurations and subscriptions|設定與訂閱|Конфигурации и подписки
广告过滤|Ad blocking|廣告過濾|Блокировка рекламы
站点延迟|Site latency|網站延遲|Задержка сайтов
更新订阅|Update subscriptions|更新訂閱|Обновить подписки
详情|Details|詳情|Подробности
上行|Upload|上行|Отправка
下行|Download|下行|Загрузка
实时网络|Live network|即時網路|Сеть в реальном времени
当前节点|Current proxy|目前節點|Текущий прокси
核心管理|Core management|核心管理|Управление ядром
应用管理|App management|應用程式管理|Управление приложениями
公网 IP 详情|Public IP details|公網 IP 詳情|Внешний IP-адрес
核心运行详情|Core runtime details|核心執行詳情|Состояние ядра
重载配置|Reload configuration|重新載入設定|Перезагрузить конфигурацию
重启代理|Restart proxy|重新啟動代理|Перезапустить прокси
流量|Traffic|流量|Трафик
总下载|Total download|總下載|Всего загружено
总上传|Total upload|總上傳|Всего отправлено
内存|Memory|記憶體|Память
运行|Uptime|執行|Время работы
网络|Network|網路|Сеть
本机地址|Local address|本機位址|Локальный адрес
网速数据来源|Traffic data source|網速資料來源|Источник данных о трафике
配置管理|Configuration management|設定管理|Управление конфигурациями
日志文件|Log files|日誌檔案|Файлы журналов
诊断工具|Diagnostics|診斷工具|Диагностика
Web面板|Web panels|Web 面板|Веб-панели
查看与处理应用文件|View and manage app files|檢視與處理應用程式檔案|Просмотр и управление файлами
运行与管理服务脚本|Run and manage service scripts|執行與管理服務指令碼|Запуск и управление скриптами
查看与导出运行日志|View and export runtime logs|檢視與匯出執行日誌|Просмотр и экспорт журналов
代理与直连|Proxy and direct routing|代理與直連|Прокси и прямое подключение
热点与代理|Hotspot and proxy|熱點與代理|Точка доступа и прокси
自动切换|Automatic switching|自動切換|Автоматическое переключение
网段与接口|Subnets and interfaces|網段與介面|Подсети и интерфейсы
导入与编辑配置文件|Import and edit configurations|匯入與編輯設定檔案|Импорт и редактирование конфигураций
订阅处理|Subscription processing|訂閱處理|Обработка подписок
规则集管理|Manage rule sets|規則集管理|Управление наборами правил
下载与更新|Download and update|下載與更新|Загрузка и обновление
规则与屏蔽|Rules and blocking|規則與封鎖|Правила и блокировки
网络与环境|Network and environment|網路與環境|Сеть и окружение
外部面板|External panels|外部面板|Внешние панели
关闭搜索|Close search|關閉搜尋|Закрыть поиск
搜索工具|Search tools|搜尋工具|Поиск инструментов
没有匹配的工具|No matching tools|沒有符合的工具|Инструменты не найдены
试试工具名称或功能关键词|Try a tool name or feature keyword|試試工具名稱或功能關鍵字|Введите название или ключевое слово
延迟|Latency|延遲|Задержка
网速|Speed|網速|Скорость
资源占用|Resources|資源使用|Ресурсы
已用|Used|已用|Использовано
总量|Total|總量|Всего
长按更新全部|Hold to update all|長按更新全部|Удерживайте, чтобы обновить всё
无在线订阅|No online subscriptions|無線上訂閱|Нет активных подписок
接口|Interface|介面|Интерфейс
地区|Region|地區|Регион
全局|Global|全域|Глобальный
直连|Direct|直連|Напрямую
""".trimIndent().lineSequence().map { it.split('|') }.associate { it[0] to it.drop(1) }

fun translateHetuText(source: String, language: String): String {
    val index = when { language.startsWith("en") -> 0; language.startsWith("zh-TW") || language.startsWith("zh-HK") || language.contains("Hant") -> 1; language.startsWith("ru") -> 2; else -> return source }
    return vocabulary[source]?.get(index) ?: source
}

@Composable fun ht(source: String): String {
    val setting = LocalHetuLanguage.current
    val language = if (setting.isBlank() || setting == "system") {
        val locales = LocalConfiguration.current.locales
        if (locales.isEmpty) "zh-CN" else locales[0].toLanguageTag()
    } else setting
    return translateHetuText(source, language)
}
