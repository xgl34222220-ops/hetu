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

// Two literals on purpose: one JVM string constant may not exceed 65535 UTF-8 bytes.
private val vocabularyBase = """
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
管理应用代理与直连规则|Manage app proxy and direct rules|管理應用程式代理與直連規則|Правила прокси и прямого подключения приложений
设置网络匹配后要执行的操作|Choose actions for network matches|設定網路符合條件後的動作|Действия при совпадении сети
管理共享网络转发相关设置|Manage shared network forwarding|管理共用網路轉送設定|Настройки общей сети
管理本地 CIDR 与接口规则|Manage local CIDR and interface rules|管理本機 CIDR 與介面規則|Локальные CIDR и правила интерфейсов
尝试其他关键词|Try another keyword|嘗試其他關鍵字|Попробуйте другое ключевое слово
关闭提示|Dismiss notice|關閉提示|Закрыть уведомление
重新读取|Read again|重新讀取|Прочитать снова
配置读取失败|Cannot read configurations|無法讀取設定|Не удалось прочитать конфигурации
编辑当前 YAML|Edit current YAML|編輯目前 YAML|Изменить текущий YAML
当前配置|Current configuration|目前設定|Текущая конфигурация
本地配置|Local configuration|本機設定|Локальная конфигурация
订阅管理|Subscription management|訂閱管理|Управление подписками
添加订阅|Add subscription|新增訂閱|Добавить подписку
编辑订阅|Edit subscription|編輯訂閱|Изменить подписку
订阅名称|Subscription name|訂閱名稱|Название подписки
订阅链接|Subscription URL|訂閱連結|URL подписки
设为当前配置|Set as current configuration|設為目前設定|Выбрать конфигурацию
导出配置|Export configuration|匯出設定|Экспорт конфигурации
重命名配置|Rename configuration|重新命名設定|Переименовать конфигурацию
删除配置|Delete configuration|刪除設定|Удалить конфигурацию
删除配置？|Delete configuration?|刪除設定？|Удалить конфигурацию?
删除订阅？|Delete subscription?|刪除訂閱？|Удалить подписку?
放弃|Discard|捨棄|Отменить изменения
放弃填写？|Discard this form?|捨棄填寫內容？|Отменить заполнение?
导入配置|Import configuration|匯入設定|Импорт конфигурации
从文件导入|Import from file|從檔案匯入|Импорт из файла
从链接导入|Import from URL|從連結匯入|Импорт по URL
配置链接|Configuration URL|設定連結|URL конфигурации
配置文件|Configuration file|設定檔案|Файл конфигурации
配置名称（可选）|Configuration name (optional)|設定名稱（選填）|Название конфигурации (необязательно)
选择文件|Choose file|選擇檔案|Выбрать файл
重新选择文件|Choose another file|重新選擇檔案|Выбрать другой файл
黑名单|Blacklist|黑名單|Чёрный список
白名单|Whitelist|白名單|Белый список
核心|Core|核心|Ядро
搜索应用或包名|Search app or package name|搜尋應用程式或套件名稱|Поиск приложения или пакета
没有匹配的应用|No matching apps|沒有符合的應用程式|Приложения не найдены
检查更新|Check for updates|檢查更新|Проверить обновления
核心状态读取失败|Cannot read core status|無法讀取核心狀態|Не удалось получить состояние ядра
仅下载管理|Download management only|僅供下載管理|Только управление загрузками
有更新|Update available|有可用更新|Доступно обновление
已是最新|Up to date|已是最新版本|Последняя версия
处理中|Working|處理中|Обработка
禁用接口|Disabled interfaces|停用介面|Отключённые интерфейсы
添加一项|Add an item|新增一項|Добавить элемент
共享网络接口|Shared network interfaces|共用網路介面|Интерфейсы общей сети
下游设备|Connected devices|下游裝置|Подключённые устройства
启用共享网络|Enable shared network|啟用共用網路|Включить общую сеть
MAC 列表|MAC list|MAC 清單|Список MAC
MAC 地址|MAC address|MAC 位址|MAC-адрес
数据源|Data source|資料來源|Источник данных
诊断与维护|Diagnostics and maintenance|診斷與維護|Диагностика и обслуживание
运行预检|Runtime preflight|執行預檢|Предварительная проверка
开始预检|Run preflight|開始預檢|Запустить проверку
查看|Inspect|檢視|Просмотр
消息与网络诊断|Messaging and network diagnostics|訊息與網路診斷|Диагностика сообщений и сети
运行记录|Runtime records|執行紀錄|Записи выполнения
网络事件记录|Network event records|網路事件紀錄|Журнал сетевых событий
紧急|Emergency|緊急|Экстренные действия
恢复网络|Restore network|恢復網路|Восстановить сеть
恢复网络？|Restore network?|恢復網路？|Восстановить сеть?
恢复|Restore|恢復|Восстановить
广告过滤说明|Ad filtering help|廣告過濾說明|Справка по фильтрации рекламы
说明|Help|說明|Справка
重新检测|Check again|重新檢測|Проверить снова
保护中|Protected|保護中|Защита активна
运行链未确认|Runtime chain unverified|執行鏈尚未確認|Рабочая цепочка не подтверждена
已开启|Enabled|已開啟|Включено
已关闭|Disabled|已關閉|Выключено
切到规则|Switch to rule mode|切換至規則模式|Переключить в режим правил
运行链验证|Runtime chain verification|執行鏈驗證|Проверка рабочей цепочки
最近拦截|Recent blocks|最近攔截|Последние блокировки
拦截强度|Filtering level|攔截強度|Уровень фильтрации
规则源|Rule sources|規則來源|Источники правил
代理关闭时|When the proxy is stopped|代理關閉時|Когда прокси остановлен
独立 DNS 过滤|Standalone DNS filtering|獨立 DNS 過濾|Отдельная фильтрация DNS
CNAME 追踪防护|CNAME tracking protection|CNAME 追蹤防護|Защита от отслеживания CNAME
语法大纲|YAML outline|語法大綱|Структура YAML
撤销|Undo|復原|Отменить
重做|Redo|重做|Повторить
保留草稿|Keep draft|保留草稿|Сохранить черновик
放弃修改？|Discard edits?|捨棄修改？|Отменить изменения?
中国大陆 IP 直连|Direct connections to mainland China IPs|中國大陸 IP 直連|Прямое подключение к IP материкового Китая
这些地址与接口在 Root 层直接放行，不进入 Mihomo。|These addresses and interfaces bypass Mihomo at the Root layer.|這些位址與介面在 Root 層直接放行，不進入 Mihomo。|Эти адреса и интерфейсы обходят Mihomo на уровне Root.
接口名不能填写 lo。修改后重启代理生效。|The interface cannot be lo. Restart the proxy to apply changes.|介面名稱不能填寫 lo。修改後重新啟動代理生效。|Интерфейс не может быть lo. Перезапустите прокси для применения.
热点、USB 与局域网转发流量|Hotspot, USB and LAN forwarded traffic|熱點、USB 與區域網路轉送流量|Пересылаемый трафик точки доступа, USB и LAN
开启后河图接管共享 / 转发流量；接口与 MAC 直连在 Root 层生效，修改后重启代理。|Hetu handles shared and forwarded traffic. Interface and MAC bypass rules apply at the Root layer after restarting the proxy.|開啟後河圖接管共用與轉送流量；介面與 MAC 直連在 Root 層生效，修改後重新啟動代理。|Hetu обрабатывает общий и пересылаемый трафик. Правила обхода интерфейсов и MAC применяются после перезапуска прокси.
将共享流量纳入透明代理|Route shared traffic through the transparent proxy|將共用流量納入透明代理|Направлять общий трафик через прозрачный прокси
没有检测到正在共享的接口。|No active sharing interfaces detected.|未偵測到正在共用的介面。|Активные общие интерфейсы не обнаружены.
没有检测到下游设备。|No connected devices detected.|未偵測到下游裝置。|Подключённые устройства не обнаружены.
接管|Proxied|接管|Через прокси
CNIP 设置|CNIP settings|CNIP 設定|Настройки CNIP
中国大陆 IPv4 / IPv6 自动直连|Automatic direct routing for mainland China IPv4 / IPv6|中國大陸 IPv4 / IPv6 自動直連|Автоматический прямой маршрут для IPv4 / IPv6 материкового Китая
CNIP 只补充 IP 级直连，不替代 YAML 中已有的域名规则。修改后重启代理生效。|CNIP adds direct IP routing while keeping the domain rules in YAML. Restart the proxy to apply changes.|CNIP 僅補充 IP 級直連，不取代 YAML 中已有的網域規則。修改後重新啟動代理生效。|CNIP добавляет прямые IP-маршруты, сохраняя правила доменов YAML. Перезапустите прокси для применения.
命中国内 IPv4 / IPv6 网段时直接连接|Connect directly when matching mainland China IPv4 / IPv6 ranges|符合中國大陸 IPv4 / IPv6 網段時直接連線|Прямое подключение к диапазонам IPv4 / IPv6 материкового Китая
内置离线快照 + Mihomo provider 运行时更新|Bundled offline snapshot + Mihomo provider runtime updates|內建離線快照 + Mihomo provider 執行時更新|Встроенный офлайн-снимок + обновления провайдера Mihomo
点击跳转到对应区段|Tap to jump to a section|點按以跳至對應區段|Нажмите для перехода к разделу
没有可识别的顶层字段。|No recognizable top-level fields.|沒有可識別的頂層欄位。|Распознаваемых полей верхнего уровня нет.
管理源配置与当前配置中的订阅链接。|Manage source configurations and subscription URLs in the current configuration.|管理來源設定與目前設定中的訂閱連結。|Управление исходными конфигурациями и URL подписок текущей конфигурации.
当前配置没有 proxy-providers。可以添加订阅，或直接编辑 YAML。|This configuration has no proxy-providers. Add a subscription or edit YAML.|目前設定沒有 proxy-providers。可以新增訂閱，或直接編輯 YAML。|В конфигурации нет proxy-providers. Добавьте подписку или измените YAML.
当前配置的 proxy-providers。|proxy-providers in the current configuration.|目前設定的 proxy-providers。|proxy-providers текущей конфигурации.
配置切换后，下次启动或重启代理时生效。|Configuration changes apply when the proxy next starts or restarts.|切換設定後，下次啟動或重新啟動代理時生效。|Изменения конфигурации применятся при следующем запуске или перезапуске прокси.
本地配置 / 内置模板 · 需要填写订阅|Local configuration / bundled template · subscription required|本機設定 / 內建範本 · 需要填寫訂閱|Локальная конфигурация / встроенный шаблон · требуется подписка
尚未填写订阅链接|Subscription URL not entered|尚未填寫訂閱連結|URL подписки не указан
未保存 · 草稿仅保留在本页|Unsaved · draft kept on this page|未儲存 · 草稿僅保留於本頁|Не сохранено · черновик хранится на этой странице
源配置 · 保存前自动校验|Source configuration · validated before saving|來源設定 · 儲存前自動驗證|Исходная конфигурация · проверка перед сохранением
导入后设为当前配置。支持 UTF-8，最大 4 MiB。|Set as the current configuration after import. UTF-8, up to 4 MiB.|匯入後設為目前設定。支援 UTF-8，最大 4 MiB。|Выбрать текущей конфигурацией после импорта. UTF-8, до 4 МиБ.
代理未运行时用本地 VPN 继续过滤广告|Keep filtering ads with a local VPN while the proxy is stopped|代理未執行時使用本機 VPN 繼續過濾廣告|Фильтровать рекламу через локальный VPN, когда прокси остановлен
拦截伪装成正常域名的追踪 CNAME|Block tracking CNAMEs disguised as normal domains|封鎖偽裝成正常網域的追蹤 CNAME|Блокировать CNAME отслеживания, замаскированные под обычные домены
点按可加入白名单|Tap to add to the allowlist|點按以加入白名單|Нажмите для добавления в белый список
没有可用的规则源。|No rule sources available.|沒有可用的規則來源。|Источников правил нет.
暂无|None yet|暫無|Пока нет
应用流量会先经过应用直连与明确白名单，再匹配广告规则 REJECT，之后才进入普通配置分流与兜底。|Traffic checks direct apps and explicit allowlists first, then ad REJECT rules, followed by regular configuration routing and fallback.|應用程式流量先經過直連應用與明確白名單，再符合廣告 REJECT 規則，之後進入一般設定分流與預設規則。|Трафик сначала проверяет прямые приложения и белые списки, затем правила REJECT рекламы, обычные маршруты и резервное правило.
代理运行时，独立 DNS 过滤会自动暂停，避免两套过滤链同时接管。|Standalone DNS filtering pauses automatically while the proxy is running.|代理執行時，獨立 DNS 過濾會自動暫停。|Отдельная фильтрация DNS автоматически приостанавливается при работающем прокси.
应用流量 → 直连应用 / 白名单 → 广告 RULE-SET → 地区 / 规则集分流 → 兜底|App traffic → direct apps / allowlist → ad RULE-SET → region / rules → fallback|應用流量 → 直連應用 / 白名單 → 廣告 RULE-SET → 地區 / 規則集分流 → 預設規則|Трафик → прямые приложения / белый список → рекламный RULE-SET → регион / правила → резерв
及其子域名将不再被拦截。|and its subdomains will no longer be blocked.|及其子網域將不再被封鎖。|и его поддомены больше не будут блокироваться.
没有可显示的应用|No apps to display|沒有可顯示的應用程式|Нет приложений для отображения
可在「更多」里显示系统应用|Show system apps from More|可於「更多」中顯示系統應用程式|Системные приложения можно показать через «Ещё»
已选|Selected|已選|Выбрано
个||個|шт.
验证 Root / TPROXY / UID / IPv6 / 绕过规则是否可用|Check Root / TPROXY / UID / IPv6 / bypass rules|驗證 Root / TPROXY / UID / IPv6 / 繞過規則是否可用|Проверка Root / TPROXY / UID / IPv6 / правил обхода
最终生成的运行副本，不修改源配置|Generated runtime copy; the source configuration stays unchanged|最終產生的執行副本，不修改來源設定|Созданная рабочая копия; исходная конфигурация не изменяется
Google / 微信连接、分流与最近运行事件|Google / WeChat connections, routing and recent runtime events|Google / 微信連線、分流與最近執行事件|Соединения Google / WeChat, маршруты и недавние события
事件与错误 ID、脱敏报告、运行记录修复与恢复诊断|Events and error IDs, redacted reports, runtime-record repair and recovery diagnostics|事件與錯誤 ID、去識別報告、執行紀錄修復與復原診斷|События и ошибки, обезличенные отчёты, исправление записей и диагностика восстановления
停止代理并回滚河图添加的 iptables / 路由规则|Stop the proxy and roll back Hetu iptables / routing rules|停止代理並復原河圖新增的 iptables / 路由規則|Остановить прокси и откатить правила iptables / маршрутов Hetu
预检、运行副本、诊断信息与紧急恢复|Preflight, runtime copies, diagnostics and emergency recovery|預檢、執行副本、診斷資訊與緊急復原|Проверка, рабочие копии, диагностика и экстренное восстановление
暂无内容。|No content yet.|暫無內容。|Пока нет содержимого.
核心按设备 ABI 从发布源直接拉取。内置核心未下载更新时使用 App 自带版本；标注「仅下载管理」的核心只做下载与版本管理。|Cores download from their release sources for the device ABI. Bundled cores use the app version until updated; download-only cores support download and version management.|核心依裝置 ABI 從發佈來源下載。內建核心未更新時使用 App 版本；僅供下載管理的核心只提供下載與版本管理。|Ядра загружаются из источников релизов для ABI устройства. Встроенные ядра используют версию приложения до обновления; остальные поддерживают только загрузку и версии.
""".trimIndent()

/** 首页 and 面板 (home/, panel/). Same format: 源|English|繁體|Русский. */
private val vocabularyHomePanel = """
未知|Unknown|未知|Неизвестно
超时|Timeout|逾時|Тайм-аут
河图|Hetu|河圖|Hetu
局域网 IP 详情|LAN IP details|區域網路 IP 詳情|Локальный IP-адрес
公网 IP|Public IP|公網 IP|Внешний IP
局域网 IP|LAN IP|區域網路 IP|Локальный IP
IP 地址|IP address|IP 位址|IP-адрес
地理位置|Location|地理位置|Местоположение
网络运营商|Network provider|網路營運商|Провайдер
城市|City|城市|Город
组织|Organization|組織|Организация
IP 类型|IP type|IP 類型|Тип IP
时区|Time zone|時區|Часовой пояс
经纬度|Coordinates|經緯度|Координаты
网络接口|Network interface|網路介面|Сетевой интерфейс
代理未运行，公网出口信息需要启动后经核心查询。|The proxy is stopped. Public exit details are looked up through the core once it starts.|代理未執行，公網出口資訊需要啟動後經核心查詢。|Прокси остановлен. Сведения о внешнем адресе запрашиваются через ядро после запуска.
这是上一次查询的结果；下拉首页或点右上角可重新查询。|This is the previous result. Pull down on Home or tap the refresh button to look it up again.|這是上一次查詢的結果；下拉首頁或點右上角可重新查詢。|Это предыдущий результат. Потяните вниз на главной или нажмите «Обновить», чтобы запросить снова.
出口信息经代理核心查询，「未知」表示查询源未返回该字段。|Exit details are looked up through the proxy core. “Unknown” means the source did not return that field.|出口資訊經代理核心查詢，「未知」表示查詢來源未回傳該欄位。|Сведения запрашиваются через ядро прокси. «Неизвестно» означает, что источник не вернул это поле.
公网信息查询失败：%s|Public address lookup failed: %s|公網資訊查詢失敗：%s|Не удалось получить внешний адрес: %s
本机直测目标|Direct probe targets|本機直測目標|Цели прямой проверки
河图进程请求，未指定代理节点|Requested by the Hetu process, with no proxy selected|河圖程序請求，未指定代理節點|Запрос от процесса Hetu без выбранного прокси
恢复默认|Restore defaults|還原預設|По умолчанию
目标 %d|Target %d|目標 %d|Цель %d
HTTP(S) 测速地址|HTTP(S) test URL|HTTP(S) 測速位址|URL проверки HTTP(S)
选择自动刷新间隔|Choose the auto refresh interval|選擇自動重新整理間隔|Выбрать интервал автообновления
首页停留时按间隔重新测速|Re-test at this interval while Home is open|停留在首頁時依間隔重新測速|Повторять проверку с этим интервалом, пока открыта главная
%d 秒|%d s|%d 秒|%d с
API 模式|API mode|API 模式|Режим API
读取 Mihomo 控制器流量，适合查看代理核心吞吐|Reads traffic from the Mihomo controller: what the proxy core carries|讀取 Mihomo 控制器流量，適合檢視代理核心吞吐|Трафик из контроллера Mihomo: то, что проходит через ядро прокси
本地|Local|本機|Локально
本地模式|Local mode|本機模式|Локальный режим
读取设备本地总流量，适合查看当前网络实际吞吐|Reads the device's total traffic: what the network actually carries|讀取裝置本機總流量，適合檢視目前網路實際吞吐|Общий трафик устройства: фактическая нагрузка сети
测速目标已保存；返回首页后立即生效|Targets saved. They apply as soon as you return to Home.|測速目標已儲存；返回首頁後立即生效|Цели сохранены и применятся при возврате на главную.
已恢复默认测速目标|Default targets restored|已還原預設測速目標|Цели по умолчанию восстановлены
测速目标名称不能为空|Target names cannot be empty|測速目標名稱不能為空|Названия целей не могут быть пустыми
三个测速目标名称不能重复|The three target names must be different|三個測速目標名稱不能重複|Названия трёх целей должны различаться
请填写有效的 http/https 测速地址|Enter a valid http/https test URL|請填寫有效的 http/https 測速位址|Укажите корректный URL http/https
少于 1 分钟|Less than a minute|少於 1 分鐘|Меньше минуты
%d 分钟|%d min|%d 分鐘|%d мин
%d 小时 %d 分钟|%d h %d min|%d 小時 %d 分鐘|%d ч %d мин
%d 天 %d 小时|%d d %d h|%d 天 %d 小時|%d дн %d ч
运行时长|Uptime|執行時間|Время работы
进程 PID|Process PID|程序 PID|PID процесса
核心版本|Core version|核心版本|Версия ядра
CPU 核心分配|CPU affinity|CPU 核心分配|Привязка к ядрам CPU
当前 CPU|Current CPU|目前 CPU|Текущий CPU
模式|Mode|模式|Режим
活动连接|Active connections|活動連線|Активные соединения
本次查看|This session|本次檢視|За этот просмотр
断开处为缺测|Gaps are missed samples|斷開處為缺測|Разрывы — пропущенные замеры
等待连续运行采样|Waiting for samples from a running core|等待連續執行取樣|Ожидание замеров работающего ядра
设置已修改，重启后生效|Settings changed. Restart to apply.|設定已修改，重新啟動後生效|Настройки изменены. Перезапустите для применения.
立即重启|Restart now|立即重新啟動|Перезапустить
已运行 %s|Running for %s|已執行 %s|Работает %s
请稍候…|Please wait…|請稍候…|Подождите…
打开基础代理配置|Open proxy configuration|開啟基本代理設定|Открыть настройку прокси
打开配置管理|Open configuration management|開啟設定管理|Открыть управление конфигурациями
启动中|Starting|啟動中|Запуск
停止中|Stopping|停止中|Остановка
直连模式，流量不经过节点|Direct mode: traffic does not go through a proxy|直連模式，流量不經過節點|Прямой режим: трафик идёт без прокси
节点信息未确认|Proxy not confirmed yet|節點資訊未確認|Прокси ещё не подтверждён
查看策略与节点|View proxy groups and proxies|檢視策略與節點|Открыть группы и прокси
本机直测|Direct probe|本機直測|Прямая проверка
由河图进程直接请求，未指定代理节点；结果不代表其他应用的代理路径。|Requested directly by the Hetu process with no proxy selected. Results do not describe the route other apps take.|由河圖程序直接請求，未指定代理節點；結果不代表其他應用程式的代理路徑。|Запрос напрямую от процесса Hetu без выбранного прокси. Результаты не отражают маршрут других приложений.
测速目标|Probe targets|測速目標|Цели проверки
重新测速|Test again|重新測速|Проверить снова
查看 IP 详情|View IP details|檢視 IP 詳情|Сведения об IP
切换到 %s|Switch to %s|切換到 %s|Переключить на %s
查询失败|Lookup failed|查詢失敗|Ошибка запроса
正在查询|Looking up|正在查詢|Запрос
等待连接|Waiting for connection|等待連線|Ожидание подключения
选择网速数据来源|Choose the traffic data source|選擇網速資料來源|Выбрать источник данных о трафике
查看订阅|View subscriptions|檢視訂閱|Открыть подписки
剩余 %d%%|%d%% left|剩餘 %d%%|Осталось %d%%
总量未知|Quota unknown|總量未知|Лимит неизвестен
查看资源占用|View resource usage|檢視資源佔用|Открыть использование ресурсов
启动失败|Start failed|啟動失敗|Не удалось запустить
查看配置|View configuration|檢視設定|Открыть конфигурацию
重新启动|Start again|重新啟動|Запустить снова
错误详情|Error details|錯誤詳情|Сведения об ошибке
测速中|Testing|測速中|Проверка
测速|Test|測速|Проверить
没有策略组|No proxy groups|沒有策略群組|Нет групп прокси
当前配置没有可显示的策略组|This configuration has no proxy groups to show|目前設定沒有可顯示的策略群組|В этой конфигурации нет групп для отображения
没有匹配的策略|No matching groups|沒有符合的策略|Нет подходящих групп
已展开|Expanded|已展開|Развёрнуто
已收起|Collapsed|已收合|Свёрнуто
收起节点|Collapse proxies|收合節點|Свернуть прокси
展开节点|Expand proxies|展開節點|Развернуть прокси
%d 个节点|%d proxies|%d 個節點|Прокси: %d
测试该组全部节点|Test every proxy in this group|測試此群組全部節點|Проверить все прокси группы
节点信息|Proxy details|節點資訊|Сведения о прокси
没有订阅|No subscriptions|沒有訂閱|Нет подписок
当前配置没有带流量信息的远程订阅|This configuration has no remote subscriptions with traffic information|目前設定沒有帶流量資訊的遠端訂閱|В конфигурации нет удалённых подписок с данными о трафике
没有匹配的订阅|No matching subscriptions|沒有符合的訂閱|Нет подходящих подписок
更新中|Updating|更新中|Обновление
更新失败：%s|Update failed: %s|更新失敗：%s|Ошибка обновления: %s
重试|Retry|重試|Повторить
到期 %s|Expires %s|到期 %s|До %s
更新于 %s|Updated %s|更新於 %s|Обновлено %s
上传|Uploaded|上傳|Отправлено
下载|Downloaded|下載|Загружено
剩余|Remaining|剩餘|Осталось
已用 %s|%s used|已用 %s|Использовано %s
总计 %s|%s total|總計 %s|Всего %s
按应用|By app|依應用程式|По приложениям
没有匹配的连接|No matching connections|沒有符合的連線|Нет подходящих соединений
当前筛选下没有连接|No connections match this filter|目前篩選下沒有連線|Нет соединений для этого фильтра
暂无连接|No connections yet|暫無連線|Соединений пока нет
新的连接建立后会显示在这里|New connections will appear here|新的連線建立後會顯示在這裡|Новые соединения появятся здесь
连接详情|Connection details|連線詳情|Сведения о соединениях
全部断开|Disconnect all|全部中斷|Разорвать все
收起|Collapse|收合|Свернуть
展开|Expand|展開|Развернуть
%d 个连接|%d connections|%d 個連線|Соединений: %d
没有规则|No rules|沒有規則|Нет правил
当前配置没有分流规则|This configuration has no routing rules|目前設定沒有分流規則|В конфигурации нет правил маршрутизации
没有匹配的规则|No matching rules|沒有符合的規則|Нет подходящих правил
，此处显示 %d 条|, %d shown here|，此處顯示 %d 條|, показано %d
按配置顺序自上而下匹配，共 %d 条|Matched top to bottom in configuration order, %d in total|依設定順序由上而下比對，共 %d 條|Проверяются сверху вниз в порядке конфигурации, всего %d
没有规则集|No rule sets|沒有規則集|Нет наборов правил
当前配置没有 rule-providers|This configuration has no rule-providers|目前設定沒有 rule-providers|В конфигурации нет rule-providers
没有匹配的规则集|No matching rule sets|沒有符合的規則集|Нет подходящих наборов правил
%d 条规则|%d rules|%d 條規則|Правил: %d
更新规则集|Update rule set|更新規則集|Обновить набор правил
没有匹配的日志|No matching logs|沒有符合的日誌|Нет подходящих записей
当前等级下暂无日志|No logs at this level yet|目前等級下暫無日誌|На этом уровне записей пока нет
暂无日志|No logs yet|暫無日誌|Записей пока нет
核心输出日志后会显示在这里|Logs appear here once the core writes them|核心輸出日誌後會顯示在這裡|Записи появятся здесь, когда ядро их выведет
搜索策略或当前节点|Search groups or current proxies|搜尋策略或目前節點|Поиск групп или текущих прокси
搜索订阅|Search subscriptions|搜尋訂閱|Поиск подписок
搜索主机或应用|Search hosts or apps|搜尋主機或應用程式|Поиск узлов или приложений
搜索规则|Search rules|搜尋規則|Поиск правил
搜索规则集|Search rule sets|搜尋規則集|Поиск наборов правил
搜索日志|Search logs|搜尋日誌|Поиск в журнале
按配置|As configured|依設定|Как в конфигурации
代理|Proxy|代理|Прокси
主机|Host|主機|Узел
类型|Type|類型|Тип
连接时间|Connected at|連線時間|Время подключения
按连接数|By connections|依連線數|По числу соединений
按总流量|By total traffic|依總流量|По общему трафику
最新在前|Newest first|最新在前|Сначала новые
最早在前|Oldest first|最早在前|Сначала старые
未归属应用|Unattributed|未歸屬應用程式|Без приложения
显示隐藏策略|Show hidden groups|顯示隱藏策略|Показывать скрытые группы
根据模式显示 GLOBAL|Show GLOBAL only in global mode|依模式顯示 GLOBAL|Показывать GLOBAL только в глобальном режиме
按订阅分组节点|Group proxies by subscription|依訂閱將節點分組|Группировать прокси по подпискам
展开新策略时折叠上一个|Collapse the previous group when opening another|展開新策略時收合上一個|Сворачивать предыдущую группу при открытии новой
切换节点后断开旧连接|Close old connections after switching proxy|切換節點後中斷舊連線|Разрывать старые соединения после смены прокси
排行只改变显示顺序，不影响代理行为。|Ranking only changes the display order. It does not affect how the proxy works.|排行只改變顯示順序，不影響代理行為。|Рейтинг меняет только порядок отображения и не влияет на работу прокси.
显示数量|Number shown|顯示數量|Количество
排序方式|Sort by|排序方式|Сортировка
按应用分组|Group by app|依應用程式分組|Группировать по приложениям
断开全部连接|Disconnect all connections|中斷全部連線|Разорвать все соединения
连接已结束|Connection ended|連線已結束|Соединение завершено
这个连接已经关闭，列表会在下次刷新时更新。|This connection has closed. The list updates on the next refresh.|這個連線已經關閉，清單會在下次重新整理時更新。|Соединение закрыто. Список обновится при следующем обновлении.
1 列|1 column|1 欄|1 столбец
2 列|2 columns|2 欄|2 столбца
排序与布局|Sorting and layout|排序與版面配置|Сортировка и вид
倒序|Reverse order|倒序|Обратный порядок
策略列数|Group columns|策略欄數|Столбцы групп
紧凑策略卡|Compact group cards|精簡策略卡|Компактные карточки групп
节点列数|Proxy columns|節點欄數|Столбцы прокси
紧凑节点卡|Compact proxy cards|精簡節點卡|Компактные карточки прокси
名称显示|Name display|名稱顯示|Отображение названий
单行截断|One line|單行截斷|Одна строка
自动换行|Wrap|自動換行|Перенос
测速与 API|Latency test and API|測速與 API|Проверка задержки и API
设置测速引擎与 API 相关选项|Latency test and API options|設定測速引擎與 API 相關選項|Параметры проверки задержки и API
测试全部节点|Test all proxies|測試全部節點|Проверить все прокси
正在测试，完成后会提示可用节点数|Testing. You will be told how many proxies are reachable.|正在測試，完成後會提示可用節點數|Идёт проверка. По завершении будет показано число доступных прокси.
对当前配置的所有节点测一次延迟|Measure the latency of every proxy in this configuration once|對目前設定的所有節點測一次延遲|Один раз измерить задержку всех прокси конфигурации
覆盖策略组图标，不修改 YAML|Override group icons without changing YAML|覆寫策略群組圖示，不修改 YAML|Заменить значки групп без изменения YAML
测速、历史采集与控制器连接|Latency test, history collection and controller connection|測速、歷史採集與控制器連線|Проверка задержки, сбор истории и подключение к контроллеру
测速地址|Test URL|測速位址|URL проверки
正在使用自定义测速 URL|Using a custom test URL|正在使用自訂測速 URL|Используется свой URL проверки
跟随订阅或策略组自带地址|Follows the URL from the subscription or the group|跟隨訂閱或策略群組自帶位址|Используется URL из подписки или группы
测速 URL|Test URL|測速 URL|URL проверки
流量与连接历史|Traffic and connection history|流量與連線歷史|История трафика и соединений
保存最近 24 小时排行所需的数据|Keeps the data needed for the last 24 hours of rankings|儲存最近 24 小時排行所需的資料|Хранит данные для рейтингов за последние 24 часа
外部 Clash API|External Clash API|外部 Clash API|Внешний Clash API
使用自定义控制器|Using a custom controller|使用自訂控制器|Используется свой контроллер
使用河图本机核心|Using Hetu's own core|使用河圖本機核心|Используется встроенное ядро Hetu
端口|Port|連接埠|Порт
密钥|Secret|金鑰|Ключ
未设置|Not set|未設定|Не задан
测速 URL 必须是完整的 HTTP/HTTPS 地址，且不能包含账号密码|The test URL must be a complete HTTP/HTTPS address without credentials|測速 URL 必須是完整的 HTTP/HTTPS 位址，且不能包含帳號密碼|URL проверки должен быть полным адресом HTTP/HTTPS без учётных данных
后端主机只填写 IPv4 或域名，不含协议和端口|Enter only an IPv4 address or a domain as host, without scheme or port|後端主機只填寫 IPv4 或網域，不含通訊協定和連接埠|В поле узла укажите только IPv4 или домен, без протокола и порта
端口范围为 1024–65535|The port must be between 1024 and 65535|連接埠範圍為 1024–65535|Порт должен быть от 1024 до 65535
Secret 不能包含换行|The secret cannot contain line breaks|Secret 不能包含換行|Ключ не может содержать переносы строк
复制名称|Copy name|複製名稱|Копировать название
协议|Protocol|通訊協定|Протокол
提供商|Provider|提供者|Провайдер
支持|Supports|支援|Поддержка
仅 TCP|TCP only|僅 TCP|Только TCP
%s 建立|Opened at %s|%s 建立|Открыто в %s
断开此连接|Disconnect this connection|中斷此連線|Разорвать это соединение
应用|App|應用程式|Приложение
包名|Package|套件名稱|Пакет
链路|Chain|鏈路|Цепочка
速率|Rate|速率|Скорость
断开全部连接？|Disconnect all connections?|中斷全部連線？|Разорвать все соединения?
所有应用会立即重新建立连接，正在进行的下载或通话可能中断。|Every app reconnects immediately. Downloads or calls in progress may be interrupted.|所有應用程式會立即重新建立連線，正在進行的下載或通話可能中斷。|Все приложения сразу переподключатся. Текущие загрузки и звонки могут прерваться.
断开全部|Disconnect all|全部中斷|Разорвать все
运行概况|Runtime summary|執行概況|Сводка
当前连接|Connections|目前連線|Соединения
%d 个订阅|%d subscriptions|%d 個訂閱|Подписок: %d
上行速度|Upload speed|上行速度|Скорость отправки
下行速度|Download speed|下行速度|Скорость загрузки
总流量|Total traffic|總流量|Общий трафик
近期趋势|Recent trend|近期趨勢|Динамика
最近 60 秒|Last 60 seconds|最近 60 秒|Последние 60 секунд
实时排行|Live ranking|即時排行|Рейтинг сейчас
暂无应用流量|No app traffic yet|暫無應用程式流量|Трафика приложений пока нет
%d 条连接|%d connections|%d 條連線|Соединений: %d
代理未运行|Proxy is not running|代理未執行|Прокси не запущен
启动代理后可查看策略与节点|Start the proxy to see groups and proxies|啟動代理後可檢視策略與節點|Запустите прокси, чтобы увидеть группы и прокси
启动代理|Start proxy|啟動代理|Запустить прокси
无法读取面板|Cannot read the panel|無法讀取面板|Не удалось прочитать панель
API 设置|API settings|API 設定|Настройки API
定位当前节点|Jump to the current proxy|定位目前節點|Перейти к текущему прокси
筛选|Filter|篩選|Фильтр
排行方式|Ranking order|排行方式|Порядок рейтинга
连接筛选|Filter connections|連線篩選|Фильтр соединений
全部更新|Update all|全部更新|Обновить все
连接排序|Sort connections|連線排序|Сортировка соединений
连接显示|Connection display|連線顯示|Вид соединений
""".trimIndent()

/** Stable UI vocabulary. Technical diagnostics and user content retain their original text. */
// Declared after both literals: top-level properties initialise in file order.
private val vocabulary = (vocabularyBase + "\n" + vocabularyHomePanel)
    .lineSequence().map { it.split('|') }.associate { it[0] to it.drop(1) }

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
