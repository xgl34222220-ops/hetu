package io.github.xgl34222220.hetu.ui

import androidx.compose.runtime.*
import android.content.SharedPreferences
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

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
同步中|Syncing|同步中|Синхронизация
正在同步核心最新状态|Syncing the latest core state|正在同步核心最新狀態|Синхронизация текущего состояния ядра
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

// 工具: the tool pages rebuilt on the shared kit, and what their components say themselves.
// "\n" in a line stands for a line break, so a two-line message can be a key.
private val vocabularyTools = """
网络测试|Network test|網路測試|Тест сети
连通性|Connectivity|連通性|Доступность
连通性与网速|Connectivity and speed|連通性與網速|Доступность и скорость
开始测速|Start test|開始測速|Начать тест
停止测速|Stop test|停止測速|Остановить тест
测试中|Testing|測試中|Проверка
未解锁|Blocked|未解鎖|Недоступно
实时速率|Live rate|即時速率|Скорость в реальном времени
测速结果|Results|測速結果|Результаты
出口|Exit|出口|Выход
重新测试|Test again|重新測試|Повторить
可用|available|可用|доступно
AI 服务|AI services|AI 服務|ИИ-сервисы
社交媒体|Social|社群媒體|Соцсети
影音娱乐|Streaming|影音娛樂|Видео и музыка
工具与服务|Tools and services|工具與服務|Инструменты и сервисы
尚未测速|Not tested yet|尚未測速|Тест не выполнялся
下载速率|Download rate|下載速率|Скорость загрузки
测速失败|Speed test failed|測速失敗|Тест не удался
失败|Failed|失敗|Ошибка
切换 WAN 和 LAN|Switch between WAN and LAN|切換 WAN 和 LAN|Переключить WAN и LAN
上一个匹配|Previous match|上一個相符項目|Предыдущее совпадение
下一个匹配|Next match|下一個相符項目|Следующее совпадение
搜索中…|Searching…|搜尋中…|Поиск…
搜索配置文本|Search configuration text|搜尋設定文字|Поиск по тексту конфигурации
清空搜索|Clear search|清除搜尋|Очистить поиск
有效规则|Active rules|有效規則|Действующие правила
代理运行后会在运行目录生成日志|Logs appear in the runtime directory once the proxy has run|代理執行後會在執行目錄產生日誌|Журналы появятся в рабочем каталоге после запуска прокси
信息|Info|資訊|Сведения
当前筛选条件下没有日志|No log entries match the current filter|目前篩選條件下沒有日誌|Нет записей для текущего фильтра
暂无日志文件|No log files yet|尚無日誌檔案|Файлов журнала пока нет
查看运行日志与调试输出|Runtime logs and debug output|檢視執行日誌與偵錯輸出|Журналы работы и отладочный вывод
清空当前日志|Clear this log|清空目前日誌|Очистить этот журнал
自动刷新中|Auto-refreshing|自動重新整理中|Автообновление включено
警告|Warning|警告|Предупреждение
调试|Debug|偵錯|Отладка
选择日志|Choose a log|選擇日誌|Выбрать журнал
错误|Error|錯誤|Ошибка
Wi‑Fi BSSID（留空不限）|Wi‑Fi BSSID (leave empty for any)|Wi‑Fi BSSID（留空不限）|Wi‑Fi BSSID (пусто — любой)
Wi‑Fi SSID（留空不限）|Wi‑Fi SSID (leave empty for any)|Wi‑Fi SSID（留空不限）|Wi‑Fi SSID (пусто — любой)
不操作|Do nothing|不動作|Ничего не делать
使用蜂窝网络时视为匹配|Counts as a match while on mobile data|使用行動網路時視為符合|Считать совпадением при мобильной сети
例如 Home-5G|e.g. Home-5G|例如 Home-5G|например, Home-5G
停止 Root 代理|Stop the root proxy|停止 Root 代理|Остановить root-прокси
关闭后不会自动启停代理|When off, the proxy is not started or stopped automatically|關閉後不會自動啟停代理|Если выключено, прокси не запускается и не останавливается автоматически
刷新当前网络|Refresh current network|重新整理目前網路|Обновить текущую сеть
匹配成功|On match|符合時|При совпадении
匹配成功动作|Action on match|符合時的動作|Действие при совпадении
启动 Root 代理|Start the root proxy|啟動 Root 代理|Запустить root-прокси
失配动作|Action on mismatch|不符合時的動作|Действие при несовпадении
当前环境|Current network|目前環境|Текущая сеть
按当前网络环境自动启停代理|Start and stop the proxy by the current network|依目前網路環境自動啟停代理|Запуск и остановка прокси по текущей сети
条件失配|On mismatch|不符合時|При несовпадении
监听服务正在运行|The watcher service is running|監聽服務執行中|Служба отслеживания работает
移动数据|Mobile data|行動數據|Мобильные данные
自动动作|Automatic actions|自動動作|Автоматические действия
自动网络匹配|Automatic network matching|自動網路比對|Автосопоставление сети
部分 Android 版本读取 SSID / BSSID 需要附近设备或定位权限；权限不足时不会猜测 Wi‑Fi 名称。|Some Android versions need the nearby devices or location permission to read the SSID / BSSID; without it the Wi‑Fi name is never guessed.|部分 Android 版本讀取 SSID / BSSID 需要鄰近裝置或定位權限；權限不足時不會猜測 Wi‑Fi 名稱。|В некоторых версиях Android для чтения SSID / BSSID нужно разрешение на устройства поблизости или геолокацию; без него имя Wi‑Fi не угадывается.
HTTPS 地址|HTTPS address|HTTPS 位址|HTTPS-адрес
仅移除面板入口，远端服务不受影响。|Only the entry is removed; the remote service is not affected.|僅移除面板入口，遠端服務不受影響。|Удаляется только ярлык панели; удалённый сервис не затрагивается.
从官方 GitHub 获取，资源保存在本机；失败时保留原版本|Fetched from the official GitHub and stored on the device; the current version is kept if it fails|從官方 GitHub 取得，資源儲存在本機；失敗時保留原版本|Загружается с официального GitHub и хранится на устройстве; при сбое остаётся текущая версия
内置|Built-in|內建|Встроенная
安装 / 更新本地 Zashboard|Install / update local Zashboard|安裝／更新本機 Zashboard|Установить / обновить локальный Zashboard
已选择|Selected|已選擇|Выбрано
未选择|Not selected|未選擇|Не выбрано
本地控制台与自定义 HTTPS 面板|Local console and custom HTTPS panels|本機主控台與自訂 HTTPS 面板|Локальная консоль и свои HTTPS-панели
本地面板模式|Local panel mode|本機面板模式|Режим локальной панели
正在更新 Zashboard…|Updating Zashboard…|正在更新 Zashboard…|Обновление Zashboard…
河图内置 / 本地 Zashboard|Hetu built-in / local Zashboard|河圖內建／本機 Zashboard|Встроенная панель Hetu / локальный Zashboard
河图本地面板|Hetu local panel|河圖本機面板|Локальная панель Hetu
河图生成的最终 Mihomo 运行副本|The final Mihomo runtime copy generated by Hetu|河圖產生的最終 Mihomo 執行副本|Итоговая рабочая копия Mihomo, созданная Hetu
添加 Web 面板|Add web panel|新增 Web 面板|Добавить веб-панель
清理 WebView 缓存、Cookie 与本地 WebStorage|Clears the WebView cache, cookies and local WebStorage|清理 WebView 快取、Cookie 與本機 WebStorage|Очищает кэш WebView, cookie и локальное WebStorage
清除 Web 缓存|Clear web cache|清除 Web 快取|Очистить веб-кэш
直接连接 127.0.0.1 Mihomo 控制器，不向局域网暴露接口|Connects straight to the Mihomo controller on 127.0.0.1; nothing is exposed to the LAN|直接連線 127.0.0.1 Mihomo 控制器，不向區域網路暴露介面|Подключается напрямую к контроллеру Mihomo на 127.0.0.1; в локальную сеть ничего не открывается
维护|Maintenance|維護|Обслуживание
编辑 Web 面板|Edit web panel|編輯 Web 面板|Изменить веб-панель
自动|Auto|自動|Авто
自动模式在未安装 Zashboard 时打开内置面板。|Auto opens the built-in panel when Zashboard is not installed.|自動模式在未安裝 Zashboard 時開啟內建面板。|В авторежиме открывается встроенная панель, если Zashboard не установлен.
自定义面板|Custom panels|自訂面板|Свои панели
还没有自定义面板。只接受 HTTPS 地址；自定义面板是否兼容当前控制器由面板自身决定。|No custom panels yet. Only HTTPS addresses are accepted; whether a panel works with this controller depends on the panel.|尚無自訂面板。僅接受 HTTPS 位址；自訂面板是否相容目前控制器由面板本身決定。|Своих панелей пока нет. Принимаются только HTTPS-адреса; совместимость с контроллером зависит от самой панели.
默认|Default|預設|По умолчанию
不设置|Not set|不設定|Не задано
代理核心启动前执行；非 0 退出码会阻止手动启动|Runs before the proxy core starts; a non-zero exit code blocks a manual start|代理核心啟動前執行；非 0 結束代碼會阻止手動啟動|Выполняется перед запуском ядра; ненулевой код возврата блокирует ручной запуск
代理网络恢复完成后执行；非 0 退出码会报告失败|Runs after the network has been restored; a non-zero exit code is reported as a failure|代理網路還原完成後執行；非 0 結束代碼會回報失敗|Выполняется после восстановления сети; ненулевой код возврата считается сбоем
删除脚本|Delete script|刪除指令碼|Удалить скрипт
在这里输入脚本|Type the script here|在這裡輸入指令碼|Введите скрипт здесь
导入的脚本会列在这里，可以随时手动执行。|Imported scripts are listed here and can be run by hand at any time.|匯入的指令碼會列在這裡，可以隨時手動執行。|Импортированные скрипты появятся здесь; их можно запускать вручную в любой момент.
导入自定义脚本|Import custom script|匯入自訂指令碼|Импорт своего скрипта
已设置|Set|已設定|Задано
执行|Run|執行|Выполнить
暂无脚本|No scripts yet|尚無指令碼|Скриптов пока нет
更多|More|更多|Ещё
服务停止后|After the service stops|服務停止後|После остановки службы
服务启动前|Before the service starts|服務啟動前|Перед запуском службы
脚本内容|Script content|指令碼內容|Содержимое скрипта
脚本环境|Script environment|指令碼環境|Окружение скриптов
自定义脚本|Custom scripts|自訂指令碼|Свои скрипты
后端地址|Backend address|後端位址|Адрес бэкенда
官方前端内容由网页提供|The official front end is served by the web page|官方前端內容由網頁提供|Официальный интерфейс загружается с веб-страницы
已连接|Connected|已連線|Подключено
打开面板|Open panel|開啟面板|Открыть панель
未连接|Not connected|未連線|Не подключено
本地后端|Local backend|本機後端|Локальный бэкенд
本地订阅管理面板|Local subscription manager|本機訂閱管理面板|Локальный менеджер подписок
检测|Check|檢測|Проверить
检测中|Checking|檢測中|Проверка
正在加载 Sub-Store…|Loading Sub-Store…|正在載入 Sub-Store…|Загрузка Sub-Store…
正在加载外部面板…|Loading external panel…|正在載入外部面板…|Загрузка внешней панели…
远端内容由网站提供|Remote content is served by the site|遠端內容由網站提供|Содержимое загружается с сайта
sing-box Schema 校验|sing-box schema validation|sing-box Schema 驗證|Проверка по схеме sing-box
关闭自动换行|Turn off word wrap|關閉自動換行|Выключить перенос строк
前往|Go|前往|Перейти
复制全部|Copy all|複製全部|Копировать всё
当前修改尚未保存。|Your changes have not been saved.|目前的修改尚未儲存。|Изменения не сохранены.
当前文件类型暂不支持编辑|This file type cannot be edited yet|目前檔案類型暫不支援編輯|Этот тип файла пока нельзя редактировать
换行|Wrap|換行|Перенос
放弃未保存修改？|Discard unsaved changes?|放棄未儲存的修改？|Отменить несохранённые изменения?
文件编辑|File editor|檔案編輯|Редактор файла
更新官方 Schema|Update official schema|更新官方 Schema|Обновить официальную схему
校验|Validate|驗證|Проверить
行号|Line number|行號|Номер строки
跳行|Go to|跳行|К строке
跳转到行|Go to line|跳至行|Перейти к строке
复制诊断|Copy diagnostics|複製診斷|Копировать диагностику
正在处理…|Working…|處理中…|Обработка…
搜索日志内容|Search log text|搜尋日誌內容|Поиск по журналу
暂无实时连接|No live connections|尚無即時連線|Активных соединений нет
暂无已采集的历史|No history collected yet|尚無已收集的歷史|История пока не собрана
加入|Add|加入|Добавить
加入白名单？|Add to the allowlist?|加入白名單？|Добавить в белый список?
域名|Domain|網域|Домен
添加白名单|Add to allowlist|新增白名單|Добавить в белый список
添加黑名单|Add to blocklist|新增黑名單|Добавить в чёрный список
输入域名，匹配它及其所有子域名，例如 example.com|Enter a domain; it and all its subdomains are matched, e.g. example.com|輸入網域，比對它及其所有子網域，例如 example.com|Введите домен; совпадают он и все его поддомены, например example.com
你的草稿仍保留。可以继续编辑，或确认放弃草稿后读取最新内容。|Your draft is still here. Keep editing, or discard it and load the latest content.|你的草稿仍保留。可以繼續編輯，或確認放棄草稿後讀取最新內容。|Черновик сохранён. Продолжайте правку или отмените его и загрузите актуальное содержимое.
例如 主订阅|e.g. Main subscription|例如 主訂閱|например, Основная подписка
例如 旅行.yaml|e.g. travel.yaml|例如 旅行.yaml|например, travel.yaml
保存到当前配置，运行时会尝试应用。|Saved to the current configuration and applied to the running proxy when possible.|儲存到目前設定，執行時會嘗試套用。|Сохраняется в текущую конфигурацию и по возможности применяется на лету.
保存遇到冲突|Save conflict|儲存時發生衝突|Конфликт при сохранении
删除订阅|Delete subscription|刪除訂閱|Удалить подписку
名称不能为空|Name cannot be empty|名稱不能為空|Название не может быть пустым
名称用于引用订阅，可在 YAML 编辑器中统一修改。|The name is how the subscription is referenced; rename it everywhere in the YAML editor.|名稱用於引用訂閱，可在 YAML 編輯器中統一修改。|По названию на подписку ссылаются; переименовать везде можно в редакторе YAML.
完整的 YAML 配置无需订阅；如需添加，请在编辑器中加入 proxy-providers|A complete YAML configuration needs no subscriptions; to add one, put proxy-providers in the editor|完整的 YAML 設定無需訂閱；如需新增，請在編輯器中加入 proxy-providers|Полной конфигурации YAML подписки не нужны; чтобы добавить, впишите proxy-providers в редакторе
已保存 · 当前配置|Saved · current configuration|已儲存 · 目前設定|Сохранено · текущая конфигурация
当前配置已发生变化，无法保存。|The current configuration has changed, so this cannot be saved.|目前設定已發生變化，無法儲存。|Текущая конфигурация изменилась, сохранить нельзя.
当前配置没有订阅段|This configuration has no subscription section|目前設定沒有訂閱區段|В этой конфигурации нет раздела подписок
放弃并读取|Discard and reload|放棄並讀取|Отменить и перечитать
放弃草稿并重新读取？|Discard the draft and reload?|放棄草稿並重新讀取？|Отменить черновик и перечитать?
文件已在其他位置修改|The file was changed elsewhere|檔案已在其他位置修改|Файл изменён в другом месте
未保存|Unsaved|未儲存|Не сохранено
来自运行中 Mihomo 的 proxy-providers|proxy-providers from the running Mihomo|來自執行中 Mihomo 的 proxy-providers|proxy-providers из работающего Mihomo
正在读取当前配置|Reading the current configuration|正在讀取目前設定|Чтение текущей конфигурации
没有识别到顶层字段|No top-level keys found|未識別到頂層欄位|Ключи верхнего уровня не найдены
源文件已发生变化，无法保存。|The source file has changed, so this cannot be saved.|來源檔案已發生變化，無法儲存。|Исходный файл изменился, сохранить нельзя.
编辑配置|Edit configuration|編輯設定|Правка конфигурации
订阅流量|Subscription traffic|訂閱流量|Трафик подписок
请输入有效的 http/https 链接|Enter a valid http/https link|請輸入有效的 http/https 連結|Введите корректную ссылку http/https
请选择配置文件|Choose a configuration file|請選擇設定檔案|Выберите файл конфигурации
读取配置|Read configuration|讀取設定|Чтение конфигурации
读取配置失败，保存操作已禁用|Could not read the configuration; saving is disabled|讀取設定失敗，儲存操作已停用|Не удалось прочитать конфигурацию; сохранение отключено
选择配置文件|Choose configuration file|選擇設定檔案|Выбрать файл конфигурации
配置切换后将应用到当前运行状态。|Switching configurations applies to the running proxy.|切換設定後將套用到目前的執行狀態。|После переключения конфигурация применяется к работающему прокси.
链接需直接返回 YAML 文件。|The link must return a YAML file directly.|連結需直接回傳 YAML 檔案。|Ссылка должна сразу возвращать файл YAML.
刷新日志|Refresh logs|重新整理日誌|Обновить журнал
搜索域名、应用、规则|Search domains, apps, rules|搜尋網域、應用程式、規則|Поиск доменов, приложений, правил
刷新 IP 信息|Refresh IP info|重新整理 IP 資訊|Обновить сведения об IP
更新全部订阅|Update all subscriptions|更新全部訂閱|Обновить все подписки
确定|OK|確定|ОК
（空）|(empty)|（空）|(пусто)
仅看已选|Selected only|僅看已選|Только выбранные
全选当前结果|Select all results|全選目前結果|Выбрать все результаты
刷新应用|Refresh apps|重新整理應用程式|Обновить приложения
按 UID|By UID|依 UID|По UID
按包名|By package name|依套件名稱|По имени пакета
按名称|By name|依名稱|По названию
排序|Sort|排序|Сортировка
搜索应用名称、包名或 UID|Search app name, package or UID|搜尋應用程式名稱、套件名稱或 UID|Поиск по названию, пакету или UID
改为升序|Ascending order|改為遞增|По возрастанию
改为降序|Descending order|改為遞減|По убыванию
显示系统应用|Show system apps|顯示系統應用程式|Показать системные приложения
清空名单|Clear the list|清空名單|Очистить список
隐藏系统应用|Hide system apps|隱藏系統應用程式|Скрыть системные приложения
搜索策略组、节点或订阅|Search groups, nodes or subscriptions|搜尋策略群組、節點或訂閱|Поиск групп, узлов или подписок
搜索节点|Search nodes|搜尋節點|Поиск узлов
没有匹配结果|No matches|沒有相符結果|Совпадений нет
测速本组|Test this group|測速本群組|Проверить эту группу
刷新规则|Refresh rules|重新整理規則|Обновить правила
搜索规则内容或策略|Search rule content or policy|搜尋規則內容或策略|Поиск по правилу или политике
更新全部规则集|Update all rule sets|更新全部規則集|Обновить все наборы правил
下载、更新与维护核心文件|Download, update and maintain core files|下載、更新與維護核心檔案|Загрузка, обновление и обслуживание ядер
下载到当前目录|Download to this folder|下載到目前目錄|Загрузить в эту папку
下载地址|Download address|下載位址|Адрес загрузки
例如 10.0.0.0/8 或 fd00::/8|e.g. 10.0.0.0/8 or fd00::/8|例如 10.0.0.0/8 或 fd00::/8|например, 10.0.0.0/8 или fd00::/8
例如 dummy0、tun+|e.g. dummy0, tun+|例如 dummy0、tun+|например, dummy0, tun+
保存 MAC|Save MACs|儲存 MAC|Сохранить MAC
修复运行记录|Repair runtime record|修復執行記錄|Исправить запись о запуске
修改运行核心后，下次启动或重启代理生效。下载、更新与维护在「内核管理」。|A change of runtime core takes effect the next time the proxy starts or restarts. Downloads, updates and maintenance are under Core management.|修改執行核心後，下次啟動或重新啟動代理時生效。下載、更新與維護在「核心管理」。|Смена ядра вступает в силу при следующем запуске или перезапуске прокси. Загрузка, обновление и обслуживание — в «Управлении ядром».
创建|Create|建立|Создать
将进入 PREROUTING 的共享流量纳入透明代理|Bring shared traffic entering PREROUTING into the transparent proxy|將進入 PREROUTING 的共用流量納入透明代理|Направлять общий трафик из PREROUTING в прозрачный прокси
开启后河图接管共享 / 转发流量；接口与 MAC 直连在 Root PREROUTING / FORWARD 层生效，修改后重启代理。|When on, Hetu takes over shared / forwarded traffic; interface and MAC bypasses work at the root PREROUTING / FORWARD level. Restart the proxy after a change.|開啟後河圖接管共用／轉送流量；介面與 MAC 直連在 Root PREROUTING / FORWARD 層生效，修改後請重新啟動代理。|Когда включено, Hetu перехватывает общий и пересылаемый трафик; исключения по интерфейсу и MAC действуют на уровне root PREROUTING / FORWARD. После изменений перезапустите прокси.
微信连接、保活、分流与最近运行事件|WeChat connectivity, keep-alive, routing and recent runtime events|微信連線、保活、分流與最近的執行事件|Соединение WeChat, keep-alive, маршрутизация и последние события
搜索当前目录|Search this folder|搜尋目前目錄|Поиск в этой папке
支持 HTTPS。文件名可留空，河图会从下载地址自动推断。|HTTPS is supported. Leave the file name empty and Hetu works it out from the address.|支援 HTTPS。檔案名稱可留空，河圖會從下載位址自動推斷。|Поддерживается HTTPS. Имя файла можно не указывать — Hetu определит его по адресу.
文件名（可选）|File name (optional)|檔案名稱（選填）|Имя файла (необязательно)
新名称|New name|新名稱|Новое имя
无法读取目录|Cannot read the folder|無法讀取目錄|Не удалось прочитать папку
查看切网与错误编号；满额暂停，历史保留|Network switches and error codes; recording pauses when full and history is kept|檢視切換網路與錯誤編號；滿額暫停，歷史保留|Смены сети и коды ошибок; при заполнении запись приостанавливается, история сохраняется
核对原会话基线、规则、DNS 与守护进程；保留现有连接|Checks the session baseline, rules, DNS and the daemon; existing connections are kept|核對原工作階段基準、規則、DNS 與守護行程；保留現有連線|Сверяет базовое состояние сеанса, правила, DNS и демон; текущие соединения сохраняются
正在读取接口…|Reading interfaces…|正在讀取介面…|Чтение интерфейсов…
此目录是空的|This folder is empty|此目錄是空的|Эта папка пуста
没有匹配的文件|No matching files|沒有相符的檔案|Подходящих файлов нет
点右上角菜单新建、导入或下载文件|Use the menu at the top right to create, import or download a file|點右上角選單新增、匯入或下載檔案|Создать, импортировать или загрузить файл можно через меню справа вверху
绕过 CNIP|Bypass CNIP|繞過 CNIP|Обход CNIP
试试其他文件名称|Try another file name|試試其他檔案名稱|Попробуйте другое имя файла
还没有条目|No entries yet|尚無項目|Записей пока нет
这些地址与接口在 Root 层直接放行，不进入 Mihomo|These addresses and interfaces pass straight through at the root level and never enter Mihomo|這些位址與介面在 Root 層直接放行，不進入 Mihomo|Эти адреса и интерфейсы пропускаются на уровне root и не попадают в Mihomo
选择负责 Root 代理运行的核心|Choose the core that runs the root proxy|選擇負責 Root 代理執行的核心|Выбор ядра для root-прокси
清除输入|Clear input|清除輸入|Очистить поле
Mihomo 规则链|Mihomo rule chain|Mihomo 規則鏈|Цепочка правил Mihomo
hetu-adblock 已写入运行副本|hetu-adblock is written into the runtime copy|hetu-adblock 已寫入執行副本|hetu-adblock записан в рабочую копию
代理仅负责转发，不执行广告规则。|The proxy only forwards; ad rules are not applied.|代理僅負責轉送，不執行廣告規則。|Прокси только пересылает трафик; правила рекламы не применяются.
启动代理后自动验证运行链。|The chain is verified automatically once the proxy starts.|啟動代理後自動驗證執行鏈。|Цепочка проверяется автоматически после запуска прокси.
启动配置注入|Startup configuration injection|啟動設定注入|Внедрение в конфигурацию запуска
在 Mihomo 内按域名拦截广告与追踪|Blocks ads and trackers by domain inside Mihomo|在 Mihomo 內依網域攔截廣告與追蹤|Блокирует рекламу и трекеры по доменам внутри Mihomo
实际拦截|Blocked for real|實際攔截|Фактические блокировки
尚未确认规则模式及广告规则加载；下拉刷新后重试验证。|Rule mode and the ad rules are not confirmed yet; pull down to refresh and verify again.|尚未確認規則模式及廣告規則載入；下拉重新整理後重試驗證。|Режим правил и загрузка рекламных правил ещё не подтверждены; потяните вниз, чтобы обновить и проверить снова.
广告规则只在「规则」模式下生效。|Ad rules only work in Rule mode.|廣告規則只在「規則」模式下生效。|Рекламные правила работают только в режиме «Правила».
广告过滤状态读取失败|Could not read the ad blocking state|廣告過濾狀態讀取失敗|Не удалось прочитать состояние блокировки рекламы
当前启动副本没有广告 provider|The startup copy has no ad provider|目前啟動副本沒有廣告 provider|В копии запуска нет рекламного провайдера
当前模式不会经过规则，广告过滤不会生效|This mode skips the rules, so ad blocking does nothing|目前模式不會經過規則，廣告過濾不會生效|В этом режиме правила не применяются, блокировка рекламы не работает
本地规则库|Local rule library|本機規則庫|Локальная база правил
本次拦截|Blocked this session|本次攔截|Заблокировано за сеанс
条|rules|條|правил
条有效规则|active rules|條有效規則|действующих правил
核心尚未加载广告规则|The core has not loaded the ad rules yet|核心尚未載入廣告規則|Ядро ещё не загрузило рекламные правила
核心已加载 REJECT 规则|The core has loaded the REJECT rules|核心已載入 REJECT 規則|Ядро загрузило правила REJECT
次|times|次|раз
正在更新…|Updating…|正在更新…|Обновление…
白名单（永不拦截）|Allowlist (never blocked)|白名單（永不攔截）|Белый список (никогда не блокировать)
立即更新|Update now|立即更新|Обновить сейчас
等待代理启动|Waiting for the proxy to start|等待代理啟動|Ожидание запуска прокси
黑名单（额外拦截）|Blocklist (blocked as well)|黑名單（額外攔截）|Чёрный список (блокировать дополнительно)
应用列表读取失败|Could not read the app list|應用程式清單讀取失敗|Не удалось прочитать список приложений
例如 日常.yaml|e.g. daily.yaml|例如 日常.yaml|например, daily.yaml
更多操作|More actions|更多操作|Другие действия
处理中…|Working…|處理中…|Обработка…
不采集聊天内容；密钥与完整 URL 已脱敏|No chat content is collected; keys and full URLs are redacted|不收集聊天內容；金鑰與完整 URL 已去識別化|Содержимое чатов не собирается; ключи и полные URL скрыты
将停止代理，并回滚河图添加的 iptables / 路由规则。用于网络异常时的紧急恢复。|Stops the proxy and rolls back the iptables / routing rules Hetu added. For emergency recovery when the network misbehaves.|將停止代理，並復原河圖新增的 iptables／路由規則。用於網路異常時的緊急復原。|Останавливает прокси и откатывает правила iptables и маршрутизации, добавленные Hetu. Для экстренного восстановления при сбоях сети.
当前修改还没有保存。|Your changes have not been saved yet.|目前的修改還沒有儲存。|Изменения ещё не сохранены.
插入|Insert|插入|Вставить
插入缩进|Insert indent|插入縮排|Вставить отступ
移除|Remove|移除|Удалить
同名配置会保留为副本。|A configuration with the same name is kept as a copy.|同名設定會保留為副本。|Конфигурация с таким же именем сохраняется как копия.
留空则使用链接中的文件名|Leave empty to use the file name from the link|留空則使用連結中的檔案名稱|Оставьте пустым, чтобы взять имя файла из ссылки
分流与过滤|Routing and filtering|分流與過濾|Маршрутизация и фильтрация
文件与维护|Files and maintenance|檔案與維護|Файлы и обслуживание
核心与面板|Cores and panels|核心與面板|Ядра и панели
共享网络设置读取失败|Could not read the shared network settings|共用網路設定讀取失敗|Не удалось прочитать настройки общей сети
名单内设备在 TPROXY、Redirect、DNS、UDP 防泄漏与 QUIC 链中优先直连。|Listed devices connect directly first in the TPROXY, Redirect, DNS, UDP leak-protection and QUIC chains.|名單內裝置在 TPROXY、Redirect、DNS、UDP 防洩漏與 QUIC 鏈中優先直連。|Устройства из списка идут напрямую в цепочках TPROXY, Redirect, DNS, защиты от утечек UDP и QUIC.
最多 %d 个 MAC。| Up to %d MACs.|最多 %d 個 MAC。| Не более %d MAC.
绕过规则读取失败|Could not read the bypass rules|繞過規則讀取失敗|Не удалось прочитать правила обхода
名称用于策略组引用，可在 YAML 编辑器中统一修改。|Policy groups refer to the subscription by this name; rename it everywhere in the YAML editor.|名稱用於策略群組引用，可在 YAML 編輯器中統一修改。|По этому названию на подписку ссылаются группы; переименовать везде можно в редакторе YAML.
已填写的内容还没有保存，返回会放弃这些修改。|What you have entered is not saved yet; going back discards it.|已填寫的內容還沒有儲存，返回會放棄這些修改。|Введённое ещё не сохранено; при возврате изменения будут потеряны.
状态|Status|狀態|Состояние
请输入面板名称|Enter a panel name|請輸入面板名稱|Введите название панели
请输入有效的 HTTPS 面板地址|Enter a valid HTTPS panel address|請輸入有效的 HTTPS 面板位址|Введите корректный HTTPS-адрес панели
自动：优先本地 Zashboard|Auto: local Zashboard first|自動：優先本機 Zashboard|Авто: сначала локальный Zashboard
已安装 Zashboard 时打开本地面板，否则使用河图内置面板|Opens the local panel when Zashboard is installed, otherwise Hetu's built-in panel|已安裝 Zashboard 時開啟本機面板，否則使用河圖內建面板|Открывает локальную панель, если Zashboard установлен, иначе встроенную панель Hetu
河图内置面板|Hetu built-in panel|河圖內建面板|Встроенная панель Hetu
始终使用河图内置面板|Always use Hetu's built-in panel|一律使用河圖內建面板|Всегда использовать встроенную панель Hetu
本地 Zashboard|Local Zashboard|本機 Zashboard|Локальный Zashboard
未安装时回退河图内置面板|Falls back to Hetu's built-in panel when not installed|未安裝時回退至河圖內建面板|Если не установлен, используется встроенная панель Hetu
河图连接的是你设备上已经运行的 Sub-Store 后端，不会把订阅内容上传给河图服务器。|Hetu connects to the Sub-Store backend already running on your device; subscription content is never uploaded to a Hetu server.|河圖連線的是你裝置上已在執行的 Sub-Store 後端，不會把訂閱內容上傳給河圖伺服器。|Hetu подключается к бэкенду Sub-Store, уже работающему на устройстве; содержимое подписок не отправляется на серверы Hetu.
面板使用 Sub-Store 官方前端，并把 API 指向你填写的本机地址。|The panel uses the official Sub-Store front end and points its API at the local address you entered.|面板使用 Sub-Store 官方前端，並把 API 指向你填寫的本機位址。|Панель использует официальный интерфейс Sub-Store и направляет API на указанный локальный адрес.
若显示未安装，请先确保本地后端已启动。|If it says not installed, make sure the local backend is running first.|若顯示未安裝，請先確認本機後端已啟動。|Если показано «не установлено», сначала убедитесь, что локальный бэкенд запущен.
广告域名直接 REJECT，\n其余流量照常分流。|Ad domains are rejected outright;\nother traffic is routed as usual.|廣告網域直接 REJECT，\n其餘流量照常分流。|Рекламные домены отклоняются сразу;\nостальной трафик идёт как обычно.
输入域名，匹配它及其所有子域名，\n例如 example.com。|Enter a domain; it and all its subdomains are matched,\ne.g. example.com.|輸入網域，比對它及其所有子網域，\n例如 example.com。|Введите домен; совпадают он и все его поддомены,\nнапример example.com.
源文件已发生变化，无法保存。\n你的草稿仍保留。可以继续编辑，或明确放弃草稿后读取最新内容。|The source file has changed, so this cannot be saved.\nYour draft is still here. Keep editing, or discard it and load the latest content.|來源檔案已發生變化，無法儲存。\n你的草稿仍保留。可以繼續編輯，或明確放棄草稿後讀取最新內容。|Исходный файл изменился, сохранить нельзя.\nЧерновик сохранён. Продолжайте правку или отмените его и загрузите актуальное содержимое.
重新读取成功后，本页未保存的修改和撤销记录会被替换为当前配置的最新内容。\n读取失败会继续保留草稿。|After a successful reload, the unsaved changes and undo history on this page are replaced with the latest content of the current configuration.\nIf reading fails, the draft is kept.|重新讀取成功後，本頁未儲存的修改和復原記錄會被替換為目前設定的最新內容。\n讀取失敗會繼續保留草稿。|После успешного чтения несохранённые изменения и история отмены на этой странице заменятся актуальным содержимым текущей конфигурации.\nПри ошибке чтения черновик сохранится.
""".trimIndent()

// 设置 and its sub-pages. A JVM string constant holds 65,535 bytes at most, which is why the
// vocabulary is kept in several literals; a new batch of entries gets a literal of its own.
private val vocabularySettings = """
DNS 劫持|DNS hijacking|DNS 劫持|Перехват DNS
DNS 劫持 TCP|Hijack DNS over TCP|DNS 劫持 TCP|Перехват DNS по TCP
DNS 劫持 UDP|Hijack DNS over UDP|DNS 劫持 UDP|Перехват DNS по UDP
DNS 劫持策略|DNS hijacking mode|DNS 劫持策略|Режим перехвата DNS
Mihomo DNS 转发|Mihomo DNS forwarding|Mihomo DNS 轉送|Пересылка DNS через Mihomo
代理 TCP|Proxy TCP|代理 TCP|Проксировать TCP
代理 UDP|Proxy UDP|代理 UDP|Проксировать UDP
代理能力|Proxy capabilities|代理能力|Возможности прокси
内存限制|Memory limit|記憶體限制|Ограничение памяти
厂商防火墙|Vendor firewall|廠商防火牆|Брандмауэр производителя
启动时清理|Clear on start|啟動時清理|Очищать при запуске
性能模式|Performance mode|效能模式|Режим производительности
磁盘 I/O 权重|Disk I/O weight|磁碟 I/O 權重|Вес дискового ввода-вывода
资源限制|Resource limits|資源限制|Ограничения ресурсов
刷新频率|Refresh interval|重新整理頻率|Интервал обновления
动作|Action|動作|Действие
点击通知打开|Tapping the notification opens|點選通知開啟|При нажатии на уведомление открыть
运行目录|Runtime directory|執行目錄|Рабочий каталог
Root 透明代理与广告过滤，基于 Mihomo。|Root transparent proxy and ad blocking, built on Mihomo.|Root 透明代理與廣告過濾，基於 Mihomo。|Прозрачный root-прокси и блокировка рекламы на базе Mihomo.
内置核心|Built-in core|內建核心|Встроенное ядро
开源许可|Open-source licences|開源授權|Лицензии открытого ПО
留空则直接下载。|Leave empty to download directly.|留空則直接下載。|Оставьте пустым, чтобы загружать напрямую.
IPv6 流量同样进入代理|IPv6 traffic goes through the proxy too|IPv6 流量同樣進入代理|Трафик IPv6 тоже идёт через прокси
IPv6 直连，不经过代理|IPv6 connects directly, bypassing the proxy|IPv6 直連，不經過代理|IPv6 идёт напрямую, минуя прокси
不进核心|Bypass core|不進核心|Мимо ядра
严格防泄漏|Strict leak protection|嚴格防洩漏|Строгая защита от утечек
主题、语言与显示设置|Theme, language and display|主題、語言與顯示設定|Тема, язык и отображение
仅用 IPv4，拦截 IPv6|IPv4 only; IPv6 is blocked|僅用 IPv4，攔截 IPv6|Только IPv4; IPv6 блокируется
从文件导入 YAML 配置|Import a YAML configuration from a file|從檔案匯入 YAML 設定|Импорт конфигурации YAML из файла
从文件恢复|Restore from file|從檔案還原|Восстановить из файла
代理核心|Proxy core|代理核心|Ядро прокси
关闭系统 IPv6 外联|Turns off outbound IPv6 system-wide|關閉系統 IPv6 外連|Отключает исходящий IPv6 в системе
创建备份|Create backup|建立備份|Создать резервную копию
加速地址|Mirror address|加速位址|Адрес зеркала
启动时将必要的河图参数覆写到运行配置|On start, writes the parameters Hetu needs into the runtime configuration|啟動時將必要的河圖參數覆寫到執行設定|При запуске записывает нужные Hetu параметры в рабочую конфигурацию
启用|Enabled|啟用|Включено
启用或禁用 IPv6 支持|Turn IPv6 support on or off|啟用或停用 IPv6 支援|Включить или отключить поддержку IPv6
备份内容|What is backed up|備份內容|Что входит в копию
备份内的设置与配置将写入当前应用。同名同内容配置会复用；同名不同内容配置将保留为副本。正在运行的代理不会自动重启。|The settings and configurations in the backup are written into this app. A configuration with the same name and content is reused; one with the same name but different content is kept as a copy. A running proxy is not restarted.|備份內的設定與設定檔將寫入目前的應用程式。同名同內容的設定檔會重複使用；同名不同內容的設定檔將保留為副本。執行中的代理不會自動重新啟動。|Настройки и конфигурации из копии будут записаны в приложение. Конфигурация с тем же именем и содержимым используется повторно; с тем же именем, но другим содержимым — сохраняется как копия. Работающий прокси не перезапускается.
备份文件可能包含配置中的订阅链接与认证信息，请保存至可信位置。|A backup may contain subscription links and credentials from your configurations; keep it somewhere you trust.|備份檔案可能包含設定中的訂閱連結與驗證資訊，請儲存至可信任的位置。|Копия может содержать ссылки подписок и учётные данные из конфигураций; храните её в надёжном месте.
安装 Root 开机脚本，开机后自动启动服务|Installs a root boot script that starts the service after boot|安裝 Root 開機指令碼，開機後自動啟動服務|Устанавливает root-скрипт, запускающий службу после загрузки
导出备份|Export backup|匯出備份|Экспорт копии
导出当前配置与偏好设置|Export the current configurations and preferences|匯出目前設定與偏好設定|Экспорт текущих конфигураций и настроек
尚未安装，请先在核心管理下载|Not installed; download it in Core management first|尚未安裝，請先在核心管理下載|Не установлено; сначала загрузите в «Управлении ядром»
尚未设置|Not set|尚未設定|Не задано
尚未选择配置|No configuration selected|尚未選擇設定|Конфигурация не выбрана
应用配置|App configurations|應用程式設定|Конфигурации приложения
开机脚本已安装，开机后自动启动服务|Boot script installed; the service starts after boot|開機指令碼已安裝，開機後自動啟動服務|Скрипт установлен; служба запускается после загрузки
开机自启|Start on boot|開機自動啟動|Автозапуск при загрузке
恢复备份？|Restore backup?|還原備份？|Восстановить из копии?
查看启动配置|View startup configuration|檢視啟動設定|Открыть конфигурацию запуска
查看当前生成的运行配置文件|View the runtime configuration file as generated now|檢視目前產生的執行設定檔案|Просмотр созданного файла рабочей конфигурации
正在设置开机脚本…|Setting up the boot script…|正在設定開機指令碼…|Настройка скрипта автозапуска…
界面偏好|Interface preferences|介面偏好|Настройки интерфейса
禁用系统 IPv6|Disable system IPv6|停用系統 IPv6|Отключить IPv6 в системе
管理配置与偏好数据|Manage configuration and preference data|管理設定與偏好資料|Управление конфигурациями и настройками
自动覆写|Automatic overrides|自動覆寫|Автоподстановка
订阅与连接|Subscriptions and connections|訂閱與連線|Подписки и соединения
订阅链接与配置来源|Subscription links and configuration sources|訂閱連結與設定來源|Ссылки подписок и источники конфигураций
设置已修改，重启代理后生效|Settings changed; restart the proxy to apply them|設定已修改，重新啟動代理後生效|Настройки изменены; перезапустите прокси, чтобы применить
调整后应用到所有页面与弹窗。|Changes apply to every page and dialog.|調整後套用到所有頁面與彈出視窗。|Изменения применяются ко всем страницам и диалогам.
运行模式|Run mode|執行模式|Режим работы
选择代理核心程序|Choose the proxy core|選擇代理核心程式|Выбор ядра прокси
选择代理运行模式|Choose how the proxy runs|選擇代理執行模式|Выбор режима работы прокси
选择河图备份文件，恢复前会再次确认|Choose a Hetu backup file; you confirm once more before it is restored|選擇河圖備份檔案，還原前會再次確認|Выберите файл копии Hetu; перед восстановлением будет запрос
通过已配置的镜像下载资源|Download resources through the configured mirror|透過已設定的鏡像下載資源|Загружать ресурсы через заданное зеркало
配置库与运行偏好|Configuration library and runtime preferences|設定庫與執行偏好|Библиотека конфигураций и параметры работы
暂无启动配置|No startup configuration yet|尚無啟動設定|Конфигурации запуска пока нет
正在读取启动配置…|Reading the startup configuration…|正在讀取啟動設定…|Чтение конфигурации запуска…
重新生成|Regenerate|重新產生|Создать заново
可用变量：点一下，加到模板末尾|Variables: tap one to add it to the end of the template|可用變數：點一下，加到範本末尾|Переменные: нажмите, чтобы добавить в конец шаблона
常驻显示运行状态、网速与快捷控制|Always shows run state, speed and quick controls|常駐顯示執行狀態、網速與快捷控制|Постоянно показывает состояние, скорость и быстрые действия
显示状态通知|Show status notification|顯示狀態通知|Показывать уведомление о состоянии
通知内容|Notification text|通知內容|Текст уведомления
通知标题|Notification title|通知標題|Заголовок уведомления
通知详细设置|Notification details|通知詳細設定|Параметры уведомления
面板浮窗|Panel sheet|面板浮動視窗|Окно панели
策略浮窗|Proxies sheet|策略浮動視窗|Окно прокси
配置页|Configurations page|設定頁|Страница конфигураций
订阅页|Subscriptions page|訂閱頁|Страница подписок
隐藏通知|Hide notification|隱藏通知|Скрыть уведомление
无|None|無|Нет
推荐。TCP/UDP 全接管，性能最好|Recommended. Takes over all TCP/UDP with the best performance|推薦。TCP/UDP 全接管，效能最好|Рекомендуется. Перехватывает весь TCP/UDP, лучшая производительность
仅 TCP，兼容性最好|TCP only, the most compatible|僅 TCP，相容性最好|Только TCP, лучшая совместимость
TCP 走 Redirect，UDP 走 TPROXY|TCP through Redirect, UDP through TPROXY|TCP 走 Redirect，UDP 走 TPROXY|TCP через Redirect, UDP через TPROXY
Root 下的 TUN 虚拟网卡|A TUN virtual interface under root|Root 下的 TUN 虛擬網路卡|Виртуальный интерфейс TUN под root
eBPF 重定向到 TUN|eBPF redirect into TUN|eBPF 重新導向至 TUN|Перенаправление eBPF в TUN
暂不可用|Not available yet|暫不可用|Пока недоступно
提高核心进程的调度优先级|Raises the scheduling priority of the core process|提高核心行程的排程優先權|Повышает приоритет планирования процесса ядра
。本次已生效|. Applied to this run|。本次已生效|. Применено в этом запуске
。本次未生效：系统不允许调整|. Not applied to this run: the system refused the change|。本次未生效：系統不允許調整|. Не применено в этом запуске: система отклонила изменение
接管系统解析|Capture the system resolver|接管系統解析|Перехватывать системный резолвер
已关闭：系统解析器直接向网络的 DNS 查询|Off: the system resolver asks the network's DNS directly|已關閉：系統解析器直接向網路的 DNS 查詢|Выключено: системный резолвер обращается к DNS сети напрямую
让系统解析器的查询也进入核心，应用不再拿到被污染的地址|Sends the system resolver's lookups through the core too, so apps stop receiving poisoned addresses|讓系統解析器的查詢也進入核心，應用程式不再拿到被污染的位址|Запросы системного резолвера тоже идут через ядро, и приложения больше не получают подменённые адреса
系统设置了指定的私人 DNS：解析走它的加密通道，不经过核心的 DNS|The system uses a named Private DNS: lookups go through its encrypted channel, not the core's DNS|系統設定了指定的私人 DNS：解析走它的加密通道，不經過核心的 DNS|В системе задан частный DNS по имени: запросы идут по его шифрованному каналу, минуя DNS ядра
已生效：系统解析器的查询进入核心|Active: the system resolver's lookups go through the core|已生效：系統解析器的查詢進入核心|Работает: запросы системного резолвера идут через ядро
未生效：Root 管理器的 BusyBox 无法切换核心的用户组，仍按旧方式运行|Not active: the root manager's BusyBox cannot switch the core's group, so the old behaviour is kept|未生效：Root 管理器的 BusyBox 無法切換核心的使用者群組，仍按舊方式執行|Не работает: BusyBox менеджера root не может сменить группу ядра, сохранено прежнее поведение
只让核心在这些 CPU 上运行，如 0-3 或 0,2,4-6|Runs the core only on these CPUs, e.g. 0-3 or 0,2,4-6|只讓核心在這些 CPU 上執行，如 0-3 或 0,2,4-6|Ядро работает только на этих CPU, например 0-3 или 0,2,4-6
Go 运行时的软上限：接近时更积极回收，不会结束核心；不低于 32M|A soft limit for the Go runtime: it collects harder near the limit and never kills the core; at least 32M|Go 執行階段的軟上限：接近時更積極回收，不會結束核心；不低於 32M|Мягкий предел для среды Go: у предела память освобождается активнее, ядро не завершается; не менее 32M
0 最优先，7 最靠后|0 is the highest priority, 7 the lowest|0 最優先，7 最靠後|0 — высший приоритет, 7 — низший
删除厂商防火墙里拦截 Google 服务的规则，只在带这类规则链的系统上有用|Removes vendor firewall rules that block Google services; only useful on systems that have such chains|刪除廠商防火牆裡攔截 Google 服務的規則，只在帶有這類規則鏈的系統上有用|Удаляет правила файрвола производителя, блокирующие сервисы Google; полезно только там, где такие цепочки есть
没有找到已安装的 Google 服务，本次没有可清理的对象|No installed Google services were found, so there was nothing to clean this time|沒有找到已安裝的 Google 服務，本次沒有可清理的對象|Установленные сервисы Google не найдены, очищать было нечего
本机没有这类厂商规则链，这个开关在这台设备上不起作用|This device has no such vendor chains; the switch does nothing here|本機沒有這類廠商規則鏈，這個開關在這台裝置上不起作用|На этом устройстве нет таких цепочек производителя; переключатель здесь ничего не делает
本机有这类规则链。本次检查 %s 条，删除 %s 条|This device has such chains. Checked %s rules and removed %s this time|本機有這類規則鏈。本次檢查 %s 條，刪除 %s 條|На устройстве есть такие цепочки. В этот раз проверено правил: %s, удалено: %s
""".trimIndent()

/** Stable UI vocabulary. Technical diagnostics and user content retain their original text. */
// Declared after the literals: top-level properties initialise in file order.
private val vocabularyConnection = """
接管状态未确认 · 出口未验证|Takeover unconfirmed · Internet unverified|接管狀態未確認 · 出口未驗證|Перехват не подтверждён · Интернет не проверен
控制接口异常 · 连接状态未确认|Controller unavailable · Connections unconfirmed|控制介面異常 · 連線狀態未確認|Контроллер недоступен · Соединения не подтверждены
接管检查异常 · 出口未验证|Takeover degraded · Internet unverified|接管檢查異常 · 出口未驗證|Перехват нарушен · Интернет не проверен
接管检查通过 · 出口未验证|Local takeover checked · Internet unverified|接管檢查通過 · 出口未驗證|Локальный перехват проверен · Интернет не проверен
接管检查通过 · 出口已验证|Local takeover checked · Internet verified|接管檢查通過 · 出口已驗證|Локальный перехват проверен · Интернет проверен
配置选择|Configurations|設定選擇|Конфигурации
当前配置 · 长按查看或编辑|Current · hold to view or edit|目前設定 · 長按檢視或編輯|Текущая · удерживайте для просмотра
内置模板 · 需要填写订阅|Bundled template · needs a subscription|內建範本 · 需要填寫訂閱|Встроенный шаблон · нужна подписка
内置模板 · 跟随 Mihomo 当前配置|Bundled template · follows the current Mihomo config|內建範本 · 跟隨 Mihomo 目前設定|Встроенный шаблон · следует текущей конфигурации Mihomo
仅 Mihomo 可用|Mihomo only|僅 Mihomo 可用|Только Mihomo
仅 Mihomo / sing-box 可用|Mihomo / sing-box only|僅 Mihomo / sing-box 可用|Только Mihomo / sing-box
尚无配置|No configurations yet|尚無設定|Конфигураций пока нет
在 工具 › 配置管理 导入配置后会显示在这里|Configurations imported in Tools › Configurations appear here|在 工具 › 設定管理 匯入設定後會顯示在這裡|Импортированные в «Инструменты › Конфигурации» появятся здесь
只读查看 · 点右上角编辑|Read-only · tap edit at the top right|唯讀檢視 · 點右上角編輯|Только чтение · нажмите «Изменить» справа вверху
正在读取配置|Reading configuration|正在讀取設定|Чтение конфигурации
暂不支持运行|Not runnable yet|暫不支援執行|Пока не запускается
暂不支持：|Not supported: |暫不支援：|Не поддерживается: 
Web 界面|Web interface|Web 介面|Веб-интерфейс
""".trimIndent()

// 2026-10 UI polish (toast detail, home metric empties, network-match status, startup-config viewer).
private val vocabularyUiPolish = """
完整信息|Full message|完整訊息|Полное сообщение
暂无订阅流量信息|No subscription usage yet|暫無訂閱流量資訊|Нет данных о трафике подписки
未开启|Off|未開啟|Выключено
已匹配|Matched|已匹配|Совпало
未匹配|Not matched|未匹配|Не совпало
停止代理|Stop proxy|停止代理|Остановить прокси
隐藏密钥|Hide secrets|隱藏密鑰|Скрыть секреты
显示密钥|Show secrets|顯示密鑰|Показать секреты
不换行|No wrap|不換行|Без переноса
已隐藏订阅链接参数与 secret；复制和导出的是完整内容，请勿公开分享|Subscription URL parameters and secret are hidden; copy and export contain everything, so do not share them publicly|已隱藏訂閱連結參數與 secret；複製和匯出的是完整內容，請勿公開分享|Параметры ссылок подписки и secret скрыты; копирование и экспорт содержат всё — не публикуйте их
""".trimIndent()

// 2026-10-13 function polish: eBPF switch subtitle parts and reason tokens, log export, network type.
private val vocabularyFuncPolish = """
规则匹配|Rule matching|規則比對|Сопоставление правил
eBPF 规则匹配|eBPF rule matching|eBPF 規則比對|Сопоставление правил eBPF
用 eBPF 映射匹配分应用名单与绕过网段，名单变化时无需重启；设备不支持时自动回退到原 iptables 规则。默认关闭，重启代理后生效|Matches the per-app list and bypass networks with eBPF maps, so list changes need no restart; falls back to the iptables rules when the device lacks support. Off by default; takes effect after a proxy restart|以 eBPF 映射比對分應用名單與繞過網段，名單變更時無需重新啟動；裝置不支援時自動回退到原 iptables 規則。預設關閉，重新啟動代理後生效|Сопоставляет список приложений и обходные сети через карты eBPF, поэтому изменения списка не требуют перезапуска; без поддержки устройства используются правила iptables. По умолчанию выключено; действует после перезапуска прокси
已生效：本次会话由 eBPF 匹配名单；只改应用名单或绕过网段时，「立即重启」会改为热更新，不中断连接|Active: this session matches the lists with eBPF; when only the app list or bypass networks change, Restart now updates them live without dropping connections|已生效：本次工作階段由 eBPF 比對名單；只改應用名單或繞過網段時，「立即重新啟動」會改為熱更新，不中斷連線|Активно: в этом сеансе списки сопоставляет eBPF; если меняются только список приложений или обходные сети, «Перезапустить» обновит их на лету без разрыва соединений
本次会话未启用 eBPF 匹配，重启代理后生效|This session does not use eBPF matching; takes effect after a proxy restart|本次工作階段未啟用 eBPF 比對，重新啟動代理後生效|В этом сеансе eBPF не используется; действует после перезапуска прокси
当前运行模式不支持 eBPF 规则匹配（仅 TPROXY / Redirect / Enhance）|The current run mode does not support eBPF rule matching (TPROXY / Redirect / Enhance only)|目前執行模式不支援 eBPF 規則比對（僅 TPROXY / Redirect / Enhance）|Текущий режим не поддерживает сопоставление eBPF (только TPROXY / Redirect / Enhance)
已关闭，重启代理后停用 eBPF 匹配；本次会话仍由 eBPF 匹配名单|Turned off: eBPF matching stops after a proxy restart; this session still matches the lists with eBPF|已關閉，重新啟動代理後停用 eBPF 比對；本次工作階段仍由 eBPF 比對名單|Выключено: eBPF перестанет работать после перезапуска прокси; в этом сеансе списки всё ещё сопоставляет eBPF
已启用但映射丢失：|Enabled but the maps are gone: |已啟用但映射遺失：|Включено, но карты потеряны: 
未生效，已回退到 iptables 规则：|Not active, fell back to the iptables rules: |未生效，已回退到 iptables 規則：|Не активно, используются правила iptables: 
未返回原因|No reason returned|未回傳原因|Причина не указана
SELinux 拒绝了 bpf 调用|SELinux denied the bpf call|SELinux 拒絕了 bpf 呼叫|SELinux запретил вызов bpf
系统没有挂载 bpf 文件系统|The bpf file system is not mounted|系統沒有掛載 bpf 檔案系統|Файловая система bpf не смонтирована
内核不提供 bpf 系统调用|The kernel has no bpf system call|核心不提供 bpf 系統呼叫|Ядро не предоставляет системный вызов bpf
内核不支持按应用 UID 匹配|The kernel cannot match by app UID|核心不支援依應用 UID 比對|Ядро не умеет сопоставлять по UID приложения
内核不支持网段前缀映射|The kernel does not support prefix maps|核心不支援網段前綴映射|Ядро не поддерживает префиксные карты
iptables 不支持 bpf 匹配|iptables does not support bpf matching|iptables 不支援 bpf 比對|iptables не поддерживает сопоставление bpf
ip6tables 不支持 bpf 匹配|ip6tables does not support bpf matching|ip6tables 不支援 bpf 比對|ip6tables не поддерживает сопоставление bpf
eBPF 组件未安装|eBPF components are not installed|eBPF 元件未安裝|Компоненты eBPF не установлены
应用名单无效|The app list is invalid|應用名單無效|Список приложений недействителен
绕过网段无效|The bypass networks are invalid|繞過網段無效|Обходные сети недействительны
eBPF 程序文件无效|The eBPF program file is invalid|eBPF 程式檔案無效|Файл программы eBPF недействителен
eBPF 映射已丢失，请重启代理|The eBPF maps are gone; restart the proxy|eBPF 映射已遺失，請重新啟動代理|Карты eBPF потеряны; перезапустите прокси
旧的 eBPF 映射无法清理|Old eBPF maps could not be removed|舊的 eBPF 映射無法清理|Старые карты eBPF не удалось удалить
eBPF 组件版本不匹配|eBPF component version mismatch|eBPF 元件版本不符|Несовпадение версии компонентов eBPF
eBPF 组件运行出错|The eBPF component failed|eBPF 元件執行出錯|Ошибка компонента eBPF
当前运行模式不支持（仅 TPROXY / Redirect / Enhance）|Not supported in the current run mode (TPROXY / Redirect / Enhance only)|目前執行模式不支援（僅 TPROXY / Redirect / Enhance）|Не поддерживается в текущем режиме (только TPROXY / Redirect / Enhance)
内核拒绝加载 eBPF 程序|The kernel refused to load the eBPF program|核心拒絕載入 eBPF 程式|Ядро отказалось загружать программу eBPF
内核无法创建 eBPF 映射|The kernel could not create the eBPF maps|核心無法建立 eBPF 映射|Ядро не смогло создать карты eBPF
eBPF 映射写入失败|Writing the eBPF maps failed|eBPF 映射寫入失敗|Не удалось записать карты eBPF
eBPF 映射读取失败|Reading the eBPF maps failed|eBPF 映射讀取失敗|Не удалось прочитать карты eBPF
eBPF 映射无法固定到 bpf 文件系统|The eBPF maps could not be pinned to the bpf file system|eBPF 映射無法固定到 bpf 檔案系統|Карты eBPF не удалось закрепить в файловой системе bpf
bpf 系统调用失败|The bpf system call failed|bpf 系統呼叫失敗|Системный вызов bpf не удался
内核拒绝加载 eBPF 程序（权限不足）|The kernel refused to load the eBPF program (permission denied)|核心拒絕載入 eBPF 程式（權限不足）|Ядро отказалось загружать программу eBPF (нет прав)
内核无法创建 eBPF 映射（权限不足）|The kernel could not create the eBPF maps (permission denied)|核心無法建立 eBPF 映射（權限不足）|Ядро не смогло создать карты eBPF (нет прав)
eBPF 映射写入失败（权限不足）|Writing the eBPF maps failed (permission denied)|eBPF 映射寫入失敗（權限不足）|Не удалось записать карты eBPF (нет прав)
eBPF 映射读取失败（权限不足）|Reading the eBPF maps failed (permission denied)|eBPF 映射讀取失敗（權限不足）|Не удалось прочитать карты eBPF (нет прав)
eBPF 映射无法固定到 bpf 文件系统（权限不足）|The eBPF maps could not be pinned (permission denied)|eBPF 映射無法固定到 bpf 檔案系統（權限不足）|Карты eBPF не удалось закрепить (нет прав)
bpf 系统调用失败（权限不足）|The bpf system call failed (permission denied)|bpf 系統呼叫失敗（權限不足）|Системный вызов bpf не удался (нет прав)
导出当前日志|Export this log|匯出目前日誌|Экспортировать журнал
加载更早 1600 行|Load 1600 earlier lines|載入更早 1600 行|Загрузить ещё 1600 строк
Wi‑Fi（名称待网络匹配读取）|Wi‑Fi (name read by network automation)|Wi‑Fi（名稱待網路比對讀取）|Wi‑Fi (имя прочитает автоматизация сети)
以太网|Ethernet|乙太網路|Ethernet
无网络|No network|無網路|Нет сети
尚未获取当前网络|Current network not read yet|尚未取得目前網路|Текущая сеть ещё не определена
""".trimIndent()

// 2026-10 i18n pass: pages and VM feedback that showed raw Chinese, the %s templates that replace
// string concatenation, and the short phrases of hxFriendlyError (app/HxFriendlyError.kt).
private val vocabularyCleanup = """
同时把上面的内存限制和 I/O 权重交给内核执行（memcg memory.max 与 io 权重，优先 cgroup v2，不支持时用 v1）；核心超出内存上限时可能被系统结束。默认关闭，重启代理后生效|Also let the kernel enforce the memory limit and I/O weight above (memcg memory.max and io weight, cgroup v2 first, v1 as fallback). The core may be killed when it exceeds the memory limit. Off by default; restart the proxy to apply|同時把上面的記憶體限制和 I/O 權重交給核心執行（memcg memory.max 與 io 權重，優先 cgroup v2，不支援時用 v1）；核心超出記憶體上限時可能被系統結束。預設關閉，重新啟動代理後生效|Ядро ОС также применит лимит памяти и вес I/O выше (memcg memory.max и вес io, сначала cgroup v2, иначе v1). Ядро прокси может быть завершено при превышении лимита. По умолчанию выключено; перезапустите прокси для применения
Root 守护|Root daemon|Root 守護|Root-демон
卡1 · 蜂窝数据|SIM 1 · Mobile data|卡1 · 行動數據|SIM 1 · Мобильные данные
默认数据卡在卡槽 1 且使用蜂窝网络时匹配|Matches when the default data SIM is in slot 1 and on mobile data|預設數據卡在卡槽 1 且使用行動網路時符合|Срабатывает, когда SIM для данных в слоте 1 и используются мобильные данные
卡2 · 蜂窝数据|SIM 2 · Mobile data|卡2 · 行動數據|SIM 2 · Мобильные данные
默认数据卡在卡槽 2 且使用蜂窝网络时匹配|Matches when the default data SIM is in slot 2 and on mobile data|預設數據卡在卡槽 2 且使用行動網路時符合|Срабатывает, когда SIM для данных в слоте 2 и используются мобильные данные
最近动作|Recent actions|最近動作|Последние действия
切换配置…|Switch configuration…|切換設定…|Сменить конфигурацию…
运行脚本…|Run script…|執行腳本…|Запустить скрипт…
请选择匹配时使用的配置|Choose the configuration to use on match|請選擇符合時使用的設定|Выберите конфигурацию для срабатывания
请选择匹配时运行的脚本|Choose the script to run on match|請選擇符合時執行的腳本|Выберите скрипт для срабатывания
脚本以 Root 运行，最长 12 秒，输出与退出码写入 scripts.log；环境变量 HETU_EVENT / HETU_NETWORK。|Scripts run as Root for up to 12 seconds; output and exit code go to scripts.log. Environment: HETU_EVENT / HETU_NETWORK.|腳本以 Root 執行，最長 12 秒，輸出與結束碼寫入 scripts.log；環境變數 HETU_EVENT / HETU_NETWORK。|Скрипты выполняются от Root до 12 секунд; вывод и код выхода пишутся в scripts.log. Переменные: HETU_EVENT / HETU_NETWORK.
运行中会校验新配置后重启核心；失败时恢复原配置。|While running, the new configuration is validated and the core restarted; on failure the previous configuration is restored.|執行中會校驗新設定後重新啟動核心；失敗時復原原設定。|Во время работы новая конфигурация проверяется и ядро перезапускается; при ошибке восстанавливается прежняя.
实测|Measured|實測|Измерено
文件|File|檔案|Файл
链接|Link|連結|Ссылка
剪贴板|Clipboard|剪貼簿|Буфер обмена
剪贴板内容|Clipboard content|剪貼簿內容|Содержимое буфера
YAML 文本|YAML text|YAML 文字|Текст YAML
读取剪贴板|Read clipboard|讀取剪貼簿|Прочитать буфер
行|lines|行|строк
请先读取剪贴板|Read the clipboard first|請先讀取剪貼簿|Сначала прочитайте буфер обмена
导入前先用 Mihomo 校验，通过后设为当前配置；代理运行中会热重载，失败自动切回原配置。支持 UTF-8，最大 4 MiB。|Validated with Mihomo before import, then set as current; a running proxy hot-reloads and falls back to the previous configuration on failure. UTF-8, up to 4 MiB.|匯入前先用 Mihomo 校驗，通過後設為目前設定；代理執行中會熱重載，失敗自動切回原設定。支援 UTF-8，最大 4 MiB。|Перед импортом проверяется Mihomo и становится текущей; работающий прокси перезагружается на лету и при ошибке возвращает прежнюю. UTF-8, до 4 МиБ.
经由河图代理 · 按当前规则与节点|Through Hetu · current rules and nodes|經由河圖代理 · 依目前規則與節點|Через Hetu · текущие правила и узлы
代理未运行 · 直连测试|Proxy stopped · direct test|代理未執行 · 直連測試|Прокси остановлен · прямой тест
准备测试…|Preparing test…|準備測試…|Подготовка теста…
延迟为首包时间（DNS、握手、TLS 与首字节）。地区来自该服务所在 Cloudflare 的出口报告、同一节点上的其他服务，或直连出口；无法实测时不显示。解锁检测经系统网络（代理规则透明生效）向各服务实测，每项最长 8 秒：解锁、受限（如仅自制剧）、不可用，或失败及原因；有服务自报地区时以其为准。|Latency is time to first byte (DNS, handshake, TLS and first byte). Region comes from the service's Cloudflare exit report, other services on the same node, or the direct exit; hidden when it cannot be measured. Unlock checks query each service through the system network (proxy rules apply transparently), up to 8 seconds each: unlocked, limited (e.g. originals only), unavailable, or failed with a reason; a region reported by the service wins.|延遲為首包時間（DNS、交握、TLS 與首位元組）。地區來自該服務所在 Cloudflare 的出口報告、同一節點上的其他服務，或直連出口；無法實測時不顯示。解鎖檢測經系統網路（代理規則透明生效）向各服務實測，每項最長 8 秒：解鎖、受限（如僅自製劇）、不可用，或失敗及原因；有服務自報地區時以其為準。|Задержка — время до первого байта (DNS, рукопожатие, TLS и первый байт). Регион берётся из отчёта Cloudflare сервиса, других сервисов на том же узле или прямого выхода; скрыт, если измерить нельзя. Проверка разблокировки опрашивает каждый сервис через системную сеть (правила прокси действуют прозрачно), до 8 секунд: доступно, ограничено (например, только оригинальный контент), недоступно или ошибка с причиной; регион, сообщённый сервисом, приоритетен.
检测解锁…|Checking unlocks…|檢測解鎖…|Проверка разблокировки…
出口 IP|Exit IP|出口 IP|Внешний IP
境外|Overseas|境外|Зарубежный
境内|Domestic|境內|Внутренний
经当前代理节点连接 Cloudflare 测速服务器（speed.cloudflare.com），依次测量延迟、下载与上传，约 20 秒，会消耗流量。|Connects to the Cloudflare speed test server (speed.cloudflare.com) through the current node and measures latency, download and upload in turn. Takes about 20 seconds and uses data.|經目前代理節點連線 Cloudflare 測速伺服器（speed.cloudflare.com），依序測量延遲、下載與上傳，約 20 秒，會消耗流量。|Подключается к серверу Cloudflare (speed.cloudflare.com) через текущий узел и измеряет задержку, загрузку и отдачу. Около 20 секунд, расходует трафик.
正在连接测速服务器…|Connecting to the speed test server…|正在連線測速伺服器…|Подключение к серверу теста…
正在测量延迟…|Measuring latency…|正在測量延遲…|Измерение задержки…
正在测量下载…|Measuring download…|正在測量下載…|Измерение загрузки…
正在测量上传…|Measuring upload…|正在測量上傳…|Измерение отдачи…
覆写脚本|Override scripts|覆寫腳本|Скрипты переопределения
每次启动前按顺序执行 main(config, profile)，返回修改后的配置；河图必需的端口与接管参数随后写入。任一脚本出错或超时，本次使用未修改的配置启动。未选择配置文件时，配置完全由脚本生成。|Before each start, main(config, profile) runs in order and returns the modified configuration; Hetu's required ports and takeover settings are written afterwards. If any script fails or times out, this start uses the unmodified configuration. With no configuration file selected, the scripts generate the whole configuration.|每次啟動前依序執行 main(config, profile)，傳回修改後的設定；河圖必需的連接埠與接管參數隨後寫入。任一腳本出錯或逾時，本次使用未修改的設定啟動。未選擇設定檔時，設定完全由腳本產生。|Перед каждым запуском по порядку выполняется main(config, profile), возвращающая изменённую конфигурацию; затем Hetu записывает обязательные порты и параметры перехвата. Если скрипт падает или превышает время, запуск идёт с неизменённой конфигурацией. Без выбранного файла конфигурацию полностью создают скрипты.
上次启动|Last start|上次啟動|Последний запуск
暂无覆写脚本|No override scripts|暫無覆寫腳本|Скриптов переопределения нет
新建脚本或导入 .js 文件，可添加规则、设置 DNS、合并节点。|Create a script or import a .js file to add rules, set DNS or merge nodes.|新建腳本或匯入 .js 檔案，可新增規則、設定 DNS、合併節點。|Создайте скрипт или импортируйте .js, чтобы добавить правила, настроить DNS или объединить узлы.
覆写脚本运行结果|Override script results|覆寫腳本執行結果|Результаты скриптов переопределения
脚本名称|Script name|腳本名稱|Имя скрипта
尚无该核心的原生配置|No native configuration for this core yet|尚無該核心的原生設定|Для этого ядра нет собственной конфигурации
启动时自动转换 Mihomo 当前配置；也可在 工具 › 配置管理 导入原生配置|The current Mihomo configuration is converted at start; you can also import a native one in Tools › Configurations|啟動時自動轉換 Mihomo 目前設定；也可在 工具 › 設定管理 匯入原生設定|При запуске текущая конфигурация Mihomo конвертируется; можно также импортировать собственную в «Инструменты › Конфигурации»
启动诊断|Startup diagnostics|啟動診斷|Диагностика запуска
测速历史|Speed test history|測速歷史|История тестов скорости
还没有测速记录：策略组测速和网速测试完成后会保存在这里|No speed tests yet: group latency tests and speed tests are saved here when they finish|還沒有測速紀錄：策略組測速和網速測試完成後會儲存在這裡|Тестов пока нет: проверки задержки групп и тесты скорости сохраняются здесь после завершения
最多保留最近 %s 次。节点测速的流量按探测次数估算；网速测试的流量为实测值，未完成或样本过少时标注“估算”。|Keeps the latest %s runs. Node test data use is estimated from the probe count; speed test data use is measured, marked “estimated” when unfinished or with too few samples.|最多保留最近 %s 次。節點測速的流量依探測次數估算；網速測試的流量為實測值，未完成或樣本過少時標註「估算」。|Хранится до %s последних запусков. Трафик проверки узлов оценивается по числу запросов; трафик теста скорости измерен и помечен «оценка», если тест не завершён или выборка мала.
另有 %s 个节点未显示|%s more nodes not shown|另有 %s 個節點未顯示|Ещё %s узлов не показано
删除这条记录|Delete this record|刪除這筆紀錄|Удалить эту запись
已随核心启动|Started with the core|已隨核心啟動|Запущено вместе с ядром
广告域名在 %s 中按规则拦截；\n该核心不支持实时验证。|Ad domains are blocked by rules in %s;\nthis core cannot verify it live.|廣告網域在 %s 中依規則攔截；\n該核心不支援即時驗證。|Рекламные домены блокируются правилами в %s;\nэто ядро не поддерживает живую проверку.
当前为%s模式|Current mode: %s|目前為%s模式|Текущий режим: %s
非规则|non-rule|非規則|не «Правила»
DNS 路径|DNS path|DNS 路徑|Путь DNS
拦截实测|Block test|攔截實測|Проверка блокировки
正在向广告域名发送一次请求…|Sending one request to an ad domain…|正在向廣告網域傳送一次請求…|Отправка запроса к рекламному домену…
向已拦截域名发一次请求，读取核心的实际判定|Send one request to a blocked domain and read the core's verdict|向已攔截網域傳送一次請求，讀取核心的實際判定|Отправить запрос к заблокированному домену и прочитать решение ядра
代理启动后可实测|Available once the proxy is running|代理啟動後可實測|Доступно после запуска прокси
DNS 接管实测|DNS takeover test|DNS 接管實測|Проверка перехвата DNS
用系统解析器解析一个已拦截域名…|Resolving a blocked domain with the system resolver…|用系統解析器解析一個已攔截網域…|Разрешение заблокированного домена системным резолвером…
%s 次|%s times|%s 次|%s раз
已检查 %s 个连接|%s connections checked|已檢查 %s 個連線|Проверено соединений: %s
日志计数|Log count|日誌計數|Подсчёт по журналу
未指定接口时兼容旧行为：接管所有转发入口。建议勾选实际的共享接口。|With no interface selected, the old behaviour applies: all forwarding ingress is taken over. Selecting the actual sharing interfaces is recommended.|未指定介面時相容舊行為：接管所有轉送入口。建議勾選實際的共用介面。|Без выбранного интерфейса работает старое поведение: перехватывается весь пересылаемый трафик. Рекомендуется выбрать реальные общие интерфейсы.
内核状态|Kernel state|核心狀態|Состояние ядра ОС
gaoyifan/china-operator-ip（MIT）· 内置离线快照 + 核心每日更新，内核集合随之热更新|gaoyifan/china-operator-ip (MIT) · bundled offline snapshot + daily core updates; kernel sets update live|gaoyifan/china-operator-ip（MIT）· 內建離線快照 + 核心每日更新，核心集合隨之熱更新|gaoyifan/china-operator-ip (MIT) · встроенный офлайн-снимок + ежедневное обновление ядром; наборы ядра ОС обновляются на лету
代理已启动|Proxy started|代理已啟動|Прокси запущен
已重启，设置已生效|Restarted, settings applied|已重新啟動，設定已生效|Перезапущено, настройки применены
名单已通过 eBPF 热更新，无需重启|Lists updated live via eBPF; no restart needed|名單已透過 eBPF 熱更新，無需重新啟動|Списки обновлены через eBPF без перезапуска
切换模式失败|Failed to switch mode|切換模式失敗|Не удалось сменить режим
已断开全部连接|All connections closed|已中斷全部連線|Все соединения закрыты
开机自启设置失败|Failed to set start on boot|開機自啟設定失敗|Не удалось настроить автозапуск
当前配置没有在线订阅（proxy-providers）|The current configuration has no online subscriptions (proxy-providers)|目前設定沒有線上訂閱（proxy-providers）|В текущей конфигурации нет онлайн-подписок (proxy-providers)
操作失败|Operation failed|操作失敗|Операция не удалась
断开失败|Failed to disconnect|中斷失敗|Не удалось отключить
日志读取失败|Failed to read logs|日誌讀取失敗|Не удалось прочитать журнал
未知错误|Unknown error|未知錯誤|Неизвестная ошибка
正在停止…|Stopping…|正在停止…|Остановка…
正在启动…|Starting…|正在啟動…|Запуск…
正在读取核心最新状态，请稍候|Reading the latest core state, please wait|正在讀取核心最新狀態，請稍候|Чтение текущего состояния ядра, подождите
正在重启…|Restarting…|正在重新啟動…|Перезапуск…
正在重载配置…|Reloading configuration…|正在重新載入設定…|Перезагрузка конфигурации…
确认最终运行状态…|Confirming final state…|確認最終執行狀態…|Проверка итогового состояния…
没有可在线更新的规则集（本地/内联规则集无需更新）|No rule sets to update online (local/inline rule sets need no update)|沒有可線上更新的規則集（本機/內嵌規則集無需更新）|Нет наборов правил для онлайн-обновления (локальные/встроенные не обновляются)
测速未取得有效结果，已保留上次读数，请检查控制接口后重试|The test returned no valid results; previous readings kept. Check the controller and try again|測速未取得有效結果，已保留上次讀數，請檢查控制介面後重試|Тест не дал результатов; прежние значения сохранены. Проверьте контроллер и повторите
站点测速失败，请检查网络|Site latency test failed; check the network|站點測速失敗，請檢查網路|Проверка сайтов не удалась; проверьте сеть
策略组测速失败|Group latency test failed|策略組測速失敗|Проверка группы не удалась
节点测速请求失败|Node latency request failed|節點測速請求失敗|Запрос проверки узла не удался
规则读取失败|Failed to read rules|規則讀取失敗|Не удалось прочитать правила
规则集更新失败|Failed to update rule sets|規則集更新失敗|Не удалось обновить наборы правил
规则集读取失败|Failed to read rule sets|規則集讀取失敗|Не удалось прочитать наборы правил
订阅更新失败|Failed to update subscriptions|訂閱更新失敗|Не удалось обновить подписки
订阅读取失败|Failed to read subscriptions|訂閱讀取失敗|Не удалось прочитать подписки
重启失败|Restart failed|重新啟動失敗|Перезапуск не удался
重载失败|Reload failed|重新載入失敗|Перезагрузка не удалась
配置导入失败|Failed to import configuration|設定匯入失敗|Не удалось импортировать конфигурацию
导出失败|Export failed|匯出失敗|Экспорт не удался
无法解析服务器地址，请检查网络或 DNS|Cannot resolve the server address; check the network or DNS|無法解析伺服器位址，請檢查網路或 DNS|Не удаётся разрешить адрес сервера; проверьте сеть или DNS
连接超时，请检查网络后重试|Connection timed out; check the network and try again|連線逾時，請檢查網路後重試|Время соединения истекло; проверьте сеть и повторите
无法连接服务器，请确认地址和端口|Cannot connect to the server; check the address and port|無法連線伺服器，請確認位址和連接埠|Не удаётся подключиться; проверьте адрес и порт
网络不可达，请检查网络连接|Network unreachable; check the connection|網路無法連線，請檢查網路連線|Сеть недоступна; проверьте подключение
安全连接失败，请检查系统时间或证书|Secure connection failed; check the system time or certificate|安全連線失敗，請檢查系統時間或憑證|Сбой защищённого соединения; проверьте время или сертификат
文件不存在或无法访问|File not found or not accessible|檔案不存在或無法存取|Файл не найден или недоступен
没有访问权限|Permission denied|沒有存取權限|Нет доступа
请求被拒绝，请检查链接或访问令牌（HTTP %s）|Request rejected; check the link or token (HTTP %s)|請求被拒絕，請檢查連結或存取權杖（HTTP %s）|Запрос отклонён; проверьте ссылку или токен (HTTP %s)
地址不存在，请检查链接（HTTP 404）|Address not found; check the link (HTTP 404)|位址不存在，請檢查連結（HTTP 404）|Адрес не найден; проверьте ссылку (HTTP 404)
请求过于频繁，请稍后再试（HTTP 429）|Too many requests; try again later (HTTP 429)|請求過於頻繁，請稍後再試（HTTP 429）|Слишком много запросов; повторите позже (HTTP 429)
服务器出错，请稍后再试（HTTP %s）|Server error; try again later (HTTP %s)|伺服器出錯，請稍後再試（HTTP %s）|Ошибка сервера; повторите позже (HTTP %s)
读写失败，请重试|Read or write failed; try again|讀寫失敗，請重試|Ошибка чтения или записи; повторите
其他可管理核心|Other manageable cores|其他可管理核心|Другие управляемые ядра
不改变正在运行的代理|Does not change the running proxy|不改變正在執行的代理|Не меняет работающий прокси
""".trimIndent()

private val vocabularyPolish2 = """
测试中断|Test interrupted|測試中斷|Тест прерван
等待流量数据|Waiting for traffic data|等待流量資料|Ожидание данных трафика
已隐藏上行与下行|Upload and download are hidden|已隱藏上行與下行|Отдача и загрузка скрыты
查看完整原因|Show full reason|查看完整原因|Показать причину полностью
切换节点需要代理运行中|Switching nodes requires the proxy to be running|切換節點需要代理執行中|Для смены узла прокси должен работать
测速需要代理运行中|Latency tests require the proxy to be running|測速需要代理執行中|Для теста задержки прокси должен работать
订阅更新需要代理运行中|Updating subscriptions requires the proxy to be running|訂閱更新需要代理執行中|Для обновления подписок прокси должен работать
规则集更新需要代理运行中|Updating rule sets requires the proxy to be running|規則集更新需要代理執行中|Для обновления наборов правил прокси должен работать
请等待当前操作完成|Please wait for the current operation to finish|請等待目前操作完成|Дождитесь завершения текущей операции
流量分布|Traffic distribution|流量分布|Распределение трафика
每 5 分钟|Every 5 minutes|每 5 分鐘|Каждые 5 минут
每小时|Hourly|每小時|Каждый час
每 6 小时|Every 6 hours|每 6 小時|Каждые 6 часов
每天|Daily|每天|Ежедневно
峰值 %s|Peak %s|峰值 %s|Пик %s
↓ 下行|↓ Download|↓ 下行|↓ Загрузка
↑ 上行|↑ Upload|↑ 上行|↑ Отдача
日志导出失败|Log export failed|日誌匯出失敗|Не удалось экспортировать журнал
日志清空失败|Failed to clear log|日誌清空失敗|Не удалось очистить журнал
导入失败|Import failed|匯入失敗|Ошибка импорта
"""

// 2026-10-13 WeChat message latency: kernel-bypass switch and the battery hint on 诊断与维护.
private val vocabularyWechat = """
消息|Messaging|訊息|Сообщения
微信直连（绕过核心）|WeChat direct (bypass the core)|微信直連（繞過核心）|WeChat напрямую (в обход ядра)
仅所选应用模式下由应用名单决定微信是否代理|In selected-apps-only mode the app list decides whether WeChat is proxied|僅所選應用模式下由應用名單決定微信是否代理|В режиме «только выбранные приложения» проксирование WeChat определяет список приложений
微信流量在内核直接放行，不经过核心与代理节点，避免长连接被节点或 NAT 静默断开导致消息延迟。默认关闭，重启代理后生效|WeChat traffic is passed in the kernel without the core or a proxy node, so its long connection is not silently dropped by a node or NAT and messages are not delayed. Off by default; takes effect after a proxy restart|微信流量在核心層直接放行，不經過核心與代理節點，避免長連線被節點或 NAT 靜默中斷導致訊息延遲。預設關閉，重新啟動代理後生效|Трафик WeChat пропускается в ядре ОС мимо ядра прокси и узла, поэтому долгое соединение не обрывается узлом или NAT и сообщения не задерживаются. По умолчанию выключено; действует после перезапуска прокси
微信未加入电池优化白名单：锁屏后系统可能冻结微信，消息会在下次唤醒时才到达。请在微信的应用设置里把电池改为「无限制」并允许自启动|WeChat is not exempt from battery optimization: the system may freeze it while the screen is off and messages arrive only at the next wake-up. In WeChat's app settings set battery to Unrestricted and allow auto-start|微信未加入電池最佳化白名單：鎖定螢幕後系統可能凍結微信，訊息會在下次喚醒時才到達。請在微信的應用程式設定把電池改為「不受限制」並允許自動啟動|WeChat не исключён из оптимизации батареи: при выключенном экране система может заморозить его, и сообщения придут только при следующем пробуждении. В настройках WeChat выберите «Без ограничений» для батареи и разрешите автозапуск
打开微信设置|Open WeChat settings|開啟微信設定|Открыть настройки WeChat
""".trimIndent()

// 2026-10-13 audit3 UI: config editor go-to-line from a validation error (G2).
private val vocabularyAudit3Ui = """
跳到第 %d 行|Go to line %d|跳至第 %d 行|К строке %d
""".trimIndent()

// 2026-10-13 audit3 settings: notification preview and unsaved-edit prompt, live 资源限制 checks (same rules as hetu-root.sh).
private val vocabularyAudit3Settings = """
预览|Preview|預覽|Предпросмотр
收起预览|Hide preview|收合預覽|Скрыть предпросмотр
预览 · 示例值|Preview · sample values|預覽 · 範例值|Предпросмотр · примерные значения
通知设置的修改还没有保存。|Your notification settings have not been saved yet.|通知設定的修改還沒有儲存。|Изменения настроек уведомления ещё не сохранены.
CPU 核心分配格式无效：请填写 0-7 或 0,2,4-6 这样的核心编号（0-23）|Invalid CPU affinity: enter core numbers such as 0-7 or 0,2,4-6 (0-23)|CPU 核心分配格式無效：請填寫 0-7 或 0,2,4-6 這樣的核心編號（0-23）|Неверная привязка к CPU: укажите номера ядер, например 0-7 или 0,2,4-6 (0-23)
内存限制格式无效：请填写 128M、1G 这样的值，且不低于 32M|Invalid memory limit: enter a value such as 128M or 1G, at least 32M|記憶體限制格式無效：請填寫 128M、1G 這樣的值，且不低於 32M|Неверный лимит памяти: укажите значение вроде 128M или 1G, не меньше 32M
内存限制不能超过 16G|The memory limit cannot exceed 16G|記憶體限制不能超過 16G|Лимит памяти не может превышать 16G
磁盘 I/O 权重应为 0-7|Disk I/O weight must be 0-7|磁碟 I/O 權重應為 0-7|Вес дискового ввода-вывода должен быть от 0 до 7
本机可用 %s 个 CPU（0-%s），超出的编号不会生效|This device has %s CPUs (0-%s); numbers beyond them have no effect|本機可用 %s 個 CPU（0-%s），超出的編號不會生效|На устройстве доступно CPU: %s (0-%s); номера сверх этого не действуют
""".trimIndent()

// 2026-10-13 audit G1: start from the last startable copy of the source config.
private val vocabularyLastGood = """
用上次可启动的版本启动|Start with the last working version|用上次可啟動的版本啟動|Запустить последнюю рабочую версию
上次可启动：%s|Last working version: %s|上次可啟動：%s|Последняя рабочая версия: %s
只用于这次启动，不会修改当前配置文件|Used for this start only; your current config file is not changed|只用於這次啟動，不會修改目前的設定檔|Только для этого запуска; текущий файл конфигурации не меняется
正在运行上次可启动的版本（%s）· 源配置未修改|Running the last working version (%s) · source config unchanged|正在執行上次可啟動的版本（%s）· 來源設定未修改|Работает последняя рабочая версия (%s) · исходная конфигурация не изменена
正在用上次可启动的版本启动…|Starting with the last working version…|正在用上次可啟動的版本啟動…|Запуск последней рабочей версии…
已用上次可启动的版本启动，源配置未修改|Started with the last working version; source config unchanged|已用上次可啟動的版本啟動，來源設定未修改|Запущена последняя рабочая версия; исходная конфигурация не изменена
""".trimIndent()

// 2026-10-14 audit4 UI: controller-error fix button, start failure kept on 首页, stopped-state placeholders,
// switched-off settings, Root 守护 row, theme notes and the bundled core version on 关于 (U3–U6, U10–U13).
private val vocabularyAudit4Ui = """
设置密钥|Set secret|設定密鑰|Задать секрет
可改用上次可启动的版本启动|You can start with the last working version instead|可改用上次可啟動的版本啟動|Можно запустить последнюю рабочую версию
修改配置后可重新启动|Fix the config, then start again|修改設定後可重新啟動|Исправьте конфигурацию и запустите снова
查看原因|View reason|查看原因|Причина
忽略启动失败|Dismiss start failure|忽略啟動失敗|Скрыть ошибку запуска
上次启动失败|Last start failed|上次啟動失敗|Последний запуск не удался
代理未运行，启动后查询公网 IP|Proxy not running; the public IP is looked up after it starts|代理未執行，啟動後查詢公網 IP|Прокси не запущен; публичный IP определяется после запуска
%s · 上次|%s · last|%s · 上次|%s · прошлый
已关闭：下面的设置仍可修改，开启后生效|Off: the settings below can still be changed and apply once it is on|已關閉：下面的設定仍可修改，開啟後生效|Выключено: настройки ниже можно менять, они применятся после включения
需先开启模糊效果|Turn on blur effects first|需先開啟模糊效果|Сначала включите размытие
随系统切换|Follows the system|隨系統切換|Как в системе
始终浅色|Always light|始終淺色|Всегда светлая
始终深色|Always dark|始終深色|Всегда тёмная
代理运行时由 Root 守护执行匹配，App 被杀也不中断|While the proxy runs, the Root daemon does the matching, even if the app is killed|代理執行時由 Root 守護執行比對，App 被終止也不中斷|Пока прокси работает, сопоставление выполняет Root-демон, даже если приложение закрыто
正在执行网络匹配|Running network matching|正在執行網路比對|Выполняет сопоставление сетей
运行中（未启用网络匹配）|Running (network matching off)|執行中（未啟用網路比對）|Работает (сопоставление сетей выключено)
未运行：由 App 前台服务执行匹配|Not running: the app's foreground service does the matching|未執行：由 App 前景服務執行比對|Не запущен: сопоставление выполняет служба приложения
执行中|Active|執行中|Активен
待命|Standby|待命|Ожидание
修订 %s|Revision %s|修訂 %s|Ревизия %s
启动后显示版本|Version shown after start|啟動後顯示版本|Версия появится после запуска
""".trimIndent()


// 2026-10-14 audit4 functions: stopped 本机直测, connection details actions, subscription alerts, launcher shortcuts, update check, restore undo.
// 2026-10-15 3.3.3 polish (audit5): tools group titles (U14) and the plain copy toast.
private val vocabularyAudit5 = """
常用工具|Frequently used|常用工具|Часто используемые
分流与网络|Routing & network|分流與網路|Маршрутизация и сеть
配置与规则|Configs & rules|設定檔與規則|Конфигурации и правила
核心与维护|Core & maintenance|核心與維護|Ядро и обслуживание
已复制|Copied|已複製|Скопировано
""".trimIndent()

private val vocabularyFunc400 = """
导出诊断包|Export diagnostics|匯出診斷包|Экспорт диагностики
诊断信息、看门狗与核心日志、路由与 iptables 状态，保存为一个文本文件|Diagnostics, watchdog and core logs, routing and iptables state in one text file|診斷資訊、看門狗與核心日誌、路由與 iptables 狀態，儲存為一個文字檔|Диагностика, журналы сторожа и ядра, маршруты и iptables в одном текстовом файле
诊断包导出失败|Could not export diagnostics|診斷包匯出失敗|Не удалось экспортировать диагностику
诊断包已导出|Diagnostics exported|診斷包已匯出|Диагностика экспортирована
没有可用的文件保存应用|No app available to save files|沒有可用的檔案儲存應用|Нет приложения для сохранения файлов
下载到的是网页而不是配置，请检查链接是否正确或需要登录|The link returned a web page, not a config. Check the link or whether it needs a sign-in|下載到的是網頁而不是設定，請檢查連結是否正確或需要登入|По ссылке получена веб-страница, а не конфигурация. Проверьте ссылку или необходимость входа
隐藏|Hide|隱藏|Скрыть
已停止|Stopped|已停止|Остановлен
正在重载…|Reloading…|正在重新載入…|Перезагрузка…
正在读取代理状态…|Reading proxy status…|正在讀取代理狀態…|Чтение состояния прокси…
暂时无法读取代理状态|Proxy status is unavailable for now|暫時無法讀取代理狀態|Состояние прокси пока недоступно
河图 · 代理状态|Hetu · Proxy status|河圖 · 代理狀態|Hetu · Состояние прокси
上次操作失败|Last action failed|上次操作失敗|Последнее действие не удалось
通知栏操作失败|Notification action failed|通知列操作失敗|Действие из уведомления не удалось
代理切换失败|Could not switch the proxy|代理切換失敗|Не удалось переключить прокси
河图代理|Hetu proxy|河圖代理|Прокси Hetu
切换失败|Switch failed|切換失敗|Сбой переключения
切换中…|Switching…|切換中…|Переключение…
未获得 Root 权限，河图无法接管网络。请在 Magisk / KernelSU / APatch 中允许河图后重试。|Root access is not granted, so Hetu cannot take over the network. Allow Hetu in Magisk / KernelSU / APatch and try again.|未取得 Root 權限，河圖無法接管網路。請在 Magisk / KernelSU / APatch 中允許河圖後重試。|Нет прав Root, Hetu не может управлять сетью. Разрешите Hetu в Magisk / KernelSU / APatch и повторите.
""".trimIndent()

private val vocabularyAudit4Func = """
直连（代理未运行）|Direct (proxy not running)|直連（代理未執行）|Напрямую (прокси не запущен)
上次结果（代理未运行）|Last result (proxy not running)|上次結果（代理未執行）|Последний результат (прокси не запущен)
直连测速|Direct test|直連測速|Прямой тест
复制主机|Copy host|複製主機|Копировать хост
只看此应用|Only this app|只看此應用|Только это приложение
查看匹配规则|Show matched rule|查看匹配規則|Показать сработавшее правило
应用：%s|App: %s|應用：%s|Приложение: %s
匹配规则：%s|Matched rule: %s|匹配規則：%s|Сработавшее правило: %s
没有找到这条规则|This rule was not found|沒有找到這條規則|Правило не найдено
规则列表可能已经变化，或这条连接由内置规则匹配|The rule list may have changed, or a built-in rule matched this connection|規則列表可能已經變化，或這條連線由內建規則匹配|Список правил мог измениться, или соединение совпало со встроенным правилом
订阅提醒|Subscription alerts|訂閱提醒|Напоминания о подписке
订阅到期/流量提醒|Expiry / data alerts|訂閱到期/流量提醒|Срок и трафик подписки
到期前 3 天内或剩余流量不足 10% 时发一条通知，每个订阅每天最多一次；立即生效|Notifies within 3 days of expiry or when less than 10% of data is left, at most once a day per subscription; applies at once|到期前 3 天內或剩餘流量不足 10% 時發一則通知，每個訂閱每天最多一次；立即生效|Уведомление за 3 дня до окончания или когда осталось меньше 10% трафика, не чаще раза в день для каждой подписки; действует сразу
没有通知权限，无法发送订阅提醒|No notification permission, so subscription alerts cannot be sent|沒有通知權限，無法傳送訂閱提醒|Нет разрешения на уведомления, напоминания о подписке не отправить
订阅即将到期|Subscription expiring soon|訂閱即將到期|Подписка скоро закончится
%s 已过期|%s has expired|%s 已過期|Срок %s истёк
%s 今天到期|%s expires today|%s 今天到期|%s истекает сегодня
%s 将在 %d 天后到期|%s expires in %d days|%s 將在 %d 天後到期|%s истекает через %d дн.
订阅流量不足|Subscription data running low|訂閱流量不足|Заканчивается трафик подписки
%s 剩余 %s（%d%%）|%s: %s left (%d%%)|%s 剩餘 %s（%d%%）|%s: осталось %s (%d%%)
启动河图代理|Start the Hetu proxy|啟動河圖代理|Запустить прокси Hetu
停止河图代理|Stop the Hetu proxy|停止河圖代理|Остановить прокси Hetu
连通性与网速测试|Connectivity and speed test|連通性與網速測試|Проверка связи и скорости
代理已在运行|The proxy is already running|代理已在執行|Прокси уже запущен
正在检查…|Checking…|正在檢查…|Проверка…
发现新版本 %s|New version %s available|發現新版本 %s|Доступна новая версия %s
当前版本 %s|Current version %s|目前版本 %s|Текущая версия %s
已是最新版本|You have the latest version|已是最新版本|Установлена последняя версия
还没有发布版本|No release has been published yet|還沒有發佈版本|Релизы ещё не опубликованы
打开发布页|Open release page|開啟發佈頁|Открыть страницу релиза
没有可打开链接的应用|No app can open this link|沒有可開啟連結的應用程式|Нет приложения для открытия ссылки
检查更新失败|Update check failed|檢查更新失敗|Не удалось проверить обновления
撤销上次恢复|Undo last restore|復原上次還原|Отменить последнее восстановление
恢复于 %s · 回到恢复前的设置，并删除恢复新增的配置副本|Restored %s · returns to the settings before it and removes the config copies it added|還原於 %s · 回到還原前的設定，並刪除還原新增的設定檔副本|Восстановлено %s · вернуть прежние настройки и удалить добавленные копии конфигураций
撤销上次恢复？|Undo the last restore?|復原上次還原？|Отменить последнее восстановление?
恢复之前的设置会写回；恢复之后又改过的设置保持不变。恢复时新增的配置副本会删除（已被选中或改过的除外），你原有的配置不受影响。正在运行的代理不会自动重启。|Settings from before the restore are written back; settings changed after it stay as they are. Config copies the restore added are deleted (unless selected or edited); your own configs are not affected. A running proxy is not restarted.|還原之前的設定會寫回；還原之後又改過的設定保持不變。還原時新增的設定檔副本會刪除（已被選取或改過的除外），你原有的設定檔不受影響。正在執行的代理不會自動重新啟動。|Настройки до восстановления будут возвращены; изменённые после него останутся. Добавленные копии конфигураций удаляются (кроме выбранных или изменённых); ваши конфигурации не затрагиваются. Запущенный прокси не перезапускается.
已撤销上次恢复|Last restore undone|已復原上次還原|Последнее восстановление отменено
撤销失败|Undo failed|復原失敗|Не удалось отменить
""".trimIndent()


// 2026-10-14 3.0.0 network recovery; 3.3.3: Wi-Fi reconnect driven by a real probe, on by default.
private val vocabularyNetRecovery300 = """
网络恢复|Network recovery|網路恢復|Восстановление сети
Wi‑Fi 无法联网时自动重连|Reconnect Wi‑Fi when it has no internet|Wi‑Fi 無法連網時自動重新連線|Переподключать Wi‑Fi, если нет интернета
启动、停止或重启代理后，以及守护进程发现整机断网时，会绕过河图直接访问国内的 204 检测地址并读取系统的 Wi‑Fi 验证状态；若 Wi‑Fi 仍无法联网，先刷新 DNS 并让系统重新检测，再用 Root 重连 Wi‑Fi（必要时关闭再打开）并发出通知。最多每 10 分钟一次、每小时 3 次。默认开启，立即生效|After the proxy starts, stops or restarts, and when the watchdog sees the whole phone offline, Hetu fetches a mainland China 204 check outside the proxy and reads the system's Wi‑Fi validation; if Wi‑Fi still has no internet, DNS caches are flushed and the system re-checks, then Root reconnects Wi‑Fi (off and on if needed) and posts a notification. At most once per 10 minutes and 3 times per hour. On by default; applies at once|啟動、停止或重新啟動代理後，以及守護程序發現整機斷網時，會繞過河圖直接存取國內的 204 偵測位址並讀取系統的 Wi‑Fi 驗證狀態；若 Wi‑Fi 仍無法連網，先清除 DNS 快取並讓系統重新偵測，再用 Root 重新連線 Wi‑Fi（必要時關閉再開啟）並發出通知。最多每 10 分鐘一次、每小時 3 次。預設開啟，立即生效|После запуска, остановки или перезапуска прокси, а также когда сторож видит, что весь телефон без сети, Hetu в обход прокси запрашивает 204-проверку в материковом Китае и читает статус проверки Wi‑Fi в системе; если интернета всё ещё нет, очищается кэш DNS и система заново проверяет сеть, затем Root переподключает Wi‑Fi (при необходимости выключает и включает) и показывает уведомление. Не чаще раза в 10 минут и 3 раз в час. По умолчанию включено, действует сразу
""".trimIndent()


// 2026-10-15 3.3.3 home: 网络延迟 card (path-true latency) and the public address seen by apps.
private val vocabularyHomeProbe333 = """
网络延迟|Latency|網路延遲|Задержка сети
经代理 · 与普通应用相同的路径|Via proxy · same path as other apps|經代理 · 與一般應用程式相同的路徑|Через прокси · тот же путь, что у приложений
直连 · 与普通应用相同的路径|Direct · same path as other apps|直連 · 與一般應用程式相同的路徑|Напрямую · тот же путь, что у приложений
等待代理状态|Waiting for the proxy|等待代理狀態|Ожидание прокси
上次结果|Last result|上次結果|Последний результат
上次 %s|Last %s|上次 %s|Последний %s
经代理查询，与普通应用看到的出口相同。|Looked up through the proxy: the same exit other apps see.|經代理查詢，與一般應用程式看到的出口相同。|Определено через прокси: тот же выход, что видят приложения.
代理未运行，显示本机直连的公网出口。|Proxy not running: this is the device's own public address.|代理未執行，顯示本機直連的公網出口。|Прокси не запущен: это собственный публичный адрес устройства.
%s；最新查询未成功，下拉首页或点右上角可重新查询。|%s; the latest lookup failed. Pull down on Home or tap the top-right button to try again.|%s；最新查詢未成功，下拉首頁或點右上角可重新查詢。|%s; последний запрос не удался. Потяните главный экран вниз или нажмите кнопку справа вверху.
「未知」表示查询源未返回该字段。|"Unknown" means the lookup service did not return that field.|「未知」表示查詢來源未回傳該欄位。|«Неизвестно» означает, что сервис не вернул это поле.
所有查询源均无响应，稍后自动重试|No lookup service answered; retrying automatically|所有查詢來源均無回應，稍後自動重試|Ни один сервис не ответил; повтор автоматически
当前没有可用网络|No network available|目前沒有可用網路|Нет доступной сети
""".trimIndent()


// 2026-10 TikTok / stop fix: start refuses rules of other proxy apps that would leave the phone offline.
private val vocabularyTiktokStop = """
检测到其他应用的防火墙规则拦截了 fake-IP 网段，河图启动后将无法上网；请先停止或卸载该代理/防火墙应用，或重启手机后再启动|Another app's firewall rule blocks the fake-IP range, so nothing would connect once Hetu starts. Stop or uninstall that proxy/firewall app, or restart the phone, then start again|偵測到其他應用程式的防火牆規則攔截了 fake-IP 網段，河圖啟動後將無法上網；請先停止或解除安裝該代理／防火牆應用程式，或重新啟動手機後再啟動|Правило брандмауэра другого приложения блокирует диапазон fake-IP, после запуска Hetu сеть работать не будет. Остановите или удалите это прокси/брандмауэр либо перезагрузите телефон и запустите снова
检测到其他代理应用的透明代理（TPROXY）规则仍在生效，河图与其同时运行会断网；请先在该应用中停止代理，或重启手机后再启动河图|Transparent proxy (TPROXY) rules of another proxy app are still active; running Hetu alongside them leaves the phone offline. Stop the proxy in that app, or restart the phone, then start Hetu|偵測到其他代理應用程式的透明代理（TPROXY）規則仍在生效，河圖與其同時執行會斷網；請先在該應用程式中停止代理，或重新啟動手機後再啟動河圖|Правила прозрачного прокси (TPROXY) другого приложения всё ещё действуют; вместе с ними Hetu оставит телефон без сети. Остановите прокси в том приложении или перезагрузите телефон, затем запустите Hetu
无法移除旧版河图停止后留下的 fake-IP 拦截规则（HXFAKE_REJECT），请重试启动或重启手机|Could not remove the fake-IP block left by an older Hetu stop (HXFAKE_REJECT). Try starting again or restart the phone|無法移除舊版河圖停止後留下的 fake-IP 攔截規則（HXFAKE_REJECT），請重試啟動或重新啟動手機|Не удалось снять блокировку fake-IP, оставленную остановкой старой версии Hetu (HXFAKE_REJECT). Запустите снова или перезагрузите телефон
""".trimIndent()


// 2026-10 CNIP without ipset (iptables tree) and the speed test failure reasons.
private val vocabularyCnipSpeed = """
国内 IPv4 直接放行，不经过核心（ipset）|Domestic IPv4 goes direct without the core (ipset)|國內 IPv4 直接放行，不經過核心（ipset）|Внутренний IPv4 идёт напрямую, минуя ядро (ipset)
设备不支持 ipset，已降级为核心内规则|This device has no ipset; the core rules handle it instead|裝置不支援 ipset，已降級為核心內規則|На устройстве нет ipset; вместо этого работают правила ядра прокси
国内 IPv4 直接放行，不经过核心（ipset；没有 ipset 时用 iptables 规则）|Domestic IPv4 goes direct without the core (ipset; iptables rules when there is no ipset)|國內 IPv4 直接放行，不經過核心（ipset；沒有 ipset 時改用 iptables 規則）|Внутренний IPv4 идёт напрямую, минуя ядро (ipset; без ipset — правила iptables)
内核 ipset 生效|Kernel ipset active|核心 ipset 生效|ipset в ядре активен
内核 iptables 规则生效（本机没有 ipset）|Kernel iptables rules active (this device has no ipset)|核心 iptables 規則生效（本機沒有 ipset）|Правила iptables в ядре активны (на устройстве нет ipset)
IPv4 %s 段|IPv4 %s ranges|IPv4 %s 段|IPv4: %s диапазонов
IPv6 %s 段|IPv6 %s ranges|IPv6 %s 段|IPv6: %s диапазонов
IPv6 未生效：需要 IPv6「启用 / 严格」模式|IPv6 not applied: needs IPv6 mode "Enable / Strict"|IPv6 未生效：需要 IPv6「啟用／嚴格」模式|IPv6 не применён: нужен режим IPv6 «Включить / Строгий»
内核绕过规则已丢失，已降级为核心内规则；重启代理可恢复|The kernel bypass rules are gone; the core rules handle it now. Restart the proxy to restore them|核心繞過規則已遺失，已降級為核心內規則；重新啟動代理可恢復|Правила обхода в ядре пропали, теперь работают правила ядра прокси. Перезапустите прокси, чтобы вернуть их
设备没有 ipset，也不支持 iptables 连接标记，已降级为核心内规则|This device has no ipset and no iptables connection marks; the core rules handle it instead|裝置沒有 ipset，也不支援 iptables 連線標記，已降級為核心內規則|На устройстве нет ipset и меток соединений iptables; вместо этого работают правила ядра прокси
当前代理模式不支持内核绕过，已降级为核心内规则|The current proxy mode has no kernel bypass; the core rules handle it instead|目前代理模式不支援核心繞過，已降級為核心內規則|Текущий режим прокси не поддерживает обход в ядре; вместо этого работают правила ядра прокси
内核 iptables 规则安装失败，已降级为核心内规则（%s）|Installing the kernel iptables rules failed; the core rules handle it instead (%s)|核心 iptables 規則安裝失敗，已降級為核心內規則（%s）|Не удалось установить правила iptables; вместо этого работают правила ядра прокси (%s)
中国 IP 数据缺失或无效，已降级为核心内规则（%s）|China IP data is missing or invalid; the core rules handle it instead (%s)|中國 IP 資料缺失或無效，已降級為核心內規則（%s）|Данные об IP Китая отсутствуют или неверны; вместо этого работают правила ядра прокси (%s)
设备没有 ipset：重启代理后改用 iptables 内核规则|This device has no ipset: restart the proxy to switch to kernel iptables rules|裝置沒有 ipset：重新啟動代理後改用 iptables 核心規則|На устройстве нет ipset: перезапустите прокси, чтобы перейти на правила iptables
内核绕过未生效，已降级为核心内规则|Kernel bypass not active; the core rules handle it instead|核心繞過未生效，已降級為核心內規則|Обход в ядре не активен; вместо этого работают правила ядра прокси
内核绕过未生效，已降级为核心内规则（%s）|Kernel bypass not active; the core rules handle it instead (%s)|核心繞過未生效，已降級為核心內規則（%s）|Обход в ядре не активен; вместо этого работают правила ядра прокси (%s)
重启代理后生效|Takes effect after a proxy restart|重新啟動代理後生效|Вступит в силу после перезапуска прокси
出口正常，但测速站 speed.cloudflare.com 的连接被立即关闭：通常是当前节点（机场）禁止测速，或分流规则把它拒绝（REJECT）。可换个节点再试|The exit works, but connections to speed.cloudflare.com are closed at once: usually the current node (provider) forbids speed tests, or a routing rule rejects it (REJECT). Try another node|出口正常，但測速站 speed.cloudflare.com 的連線被立即關閉：通常是目前節點（機場）禁止測速，或分流規則將其拒絕（REJECT）。可換個節點再試|Выход работает, но соединения с speed.cloudflare.com сразу закрываются: обычно текущий узел (провайдер) запрещает тесты скорости или правило маршрутизации его отклоняет (REJECT). Попробуйте другой узел
连接被立即关闭，出口也查询不到：当前节点可能不可用，或分流规则拒绝了这些连接。请换个节点再试|Connections are closed at once and the exit cannot be looked up either: the current node may be down, or a routing rule rejects these connections. Try another node|連線被立即關閉，出口也查詢不到：目前節點可能無法使用，或分流規則拒絕了這些連線。請換個節點再試|Соединения сразу закрываются, и выход тоже не определить: текущий узел может быть недоступен или правило маршрутизации отклоняет эти соединения. Попробуйте другой узел
无法连接河图核心的本地测速入口（127.0.0.1:%s）：代理可能刚停止或正在重启，请稍后再试|Cannot reach the core's local speed-test entry (127.0.0.1:%s): the proxy may have just stopped or be restarting. Try again shortly|無法連線河圖核心的本機測速入口（127.0.0.1:%s）：代理可能剛停止或正在重新啟動，請稍後再試|Нет связи с локальным входом теста скорости ядра (127.0.0.1:%s): прокси мог только что остановиться или перезапускается. Повторите чуть позже
测速服务器拒绝连接|The speed test server refused the connection|測速伺服器拒絕連線|Сервер теста скорости отклонил соединение
测速请求超时：当前节点响应太慢或不可用|The speed test timed out: the current node is too slow or unavailable|測速請求逾時：目前節點回應太慢或無法使用|Тайм-аут теста скорости: текущий узел слишком медленный или недоступен
无法解析测速服务器域名|Cannot resolve the speed test server's name|無法解析測速伺服器網域|Не удалось разрешить имя сервера теста скорости
与测速服务器的 TLS 握手失败（%s）|TLS handshake with the speed test server failed (%s)|與測速伺服器的 TLS 交握失敗（%s）|Сбой TLS-рукопожатия с сервером теста скорости (%s)
测速服务器返回 HTTP %s，当前出口可能被 Cloudflare 限制|The speed test server answered HTTP %s; Cloudflare may be limiting this exit|測速伺服器回傳 HTTP %s，目前出口可能被 Cloudflare 限制|Сервер теста скорости ответил HTTP %s; Cloudflare может ограничивать этот выход
测速连接中断（%s）|The speed test connection broke off (%s)|測速連線中斷（%s）|Соединение теста скорости прервалось (%s)
""".trimIndent()

private val vocabulary = listOf(vocabularyNetRecovery300, vocabularyAudit5, vocabularyBase, vocabularyHomePanel, vocabularyTools, vocabularySettings, vocabularyConnection, vocabularyUiPolish, vocabularyFuncPolish, vocabularyCleanup, vocabularyPolish2, vocabularyWechat, vocabularyAudit3Ui, vocabularyAudit3Settings, vocabularyLastGood, vocabularyAudit4Ui, vocabularyAudit4Func, vocabularyHomeProbe333, vocabularyFunc400, vocabularyTiktokStop, vocabularyCnipSpeed).joinToString("\n")
    .lineSequence().map { it.replace("\\n", "\n").split('|') }.associate { it[0] to it.drop(1) }

fun translateHetuText(source: String, language: String): String {
    val index = when { language.startsWith("en") -> 0; language.startsWith("zh-TW") || language.startsWith("zh-HK") || language.contains("Hant") -> 1; language.startsWith("ru") -> 2; else -> return source }
    vocabulary[source]?.get(index)?.let { return it }
    // hxFriendlyError messages: "<phrase> · REASON_CODE", the phrase possibly carrying an HTTP status.
    val coded = friendlyErrorShape.matchEntire(source) ?: return source
    val phrase = coded.groupValues[1]
    val translated = vocabulary[phrase]?.get(index) ?: friendlyHttpStatus.find(phrase)?.let { status ->
        vocabulary[phrase.replaceRange(status.groups[1]!!.range, "%s")]?.get(index)?.replace("%s", status.groupValues[1])
    } ?: return source
    return translated + " · " + coded.groupValues[2]
}

private val friendlyErrorShape = Regex("""(.+) · ([A-Z][A-Z0-9_]+)""", RegexOption.DOT_MATCHES_ALL)
private val friendlyHttpStatus = Regex("""（HTTP (\d{3})）""")

@Composable fun ht(source: String): String {
    val setting = LocalHetuLanguage.current
    val language = if (setting.isBlank() || setting == "system") {
        val locales = LocalConfiguration.current.locales
        if (locales.isEmpty) "zh-CN" else locales[0].toLanguageTag()
    } else setting
    return translateHetuText(source, language)
}
