# Инструкции по сборке Intelligent NPCs

## Требования
- Java 17+
- Gradle 8.0+
- Minecraft 1.21
- Fabric Loader 0.15.11+

## Сборка проекта

### Вариант 1: С установленным Gradle
```bash
gradle build
```

### Вариант 2: Загрузка gradle wrapper
```bash
# Скачать правильный gradle wrapper
curl -L https://services.gradle.org/distributions/gradle-8.4-bin.zip -o gradle.zip
unzip gradle.zip
rm gradle.zip

# Использовать загруженный gradle
./gradle-8.4/bin/gradle wrapper
./gradlew build
```

### Вариант 3: Прямая сборка через Fabric
1. Установите Fabric развертывание
2. Скопируйте исходники в папку src
3. Соберите через стандартные инструменты Fabric

## Установка мода
1. Скопируйте built/libs/intelligentnpc-1.0.0.jar в папку mods
2. Запустите Minecraft с Fabric Loader
3. Настройте LLM интеграцию:
   - Для OpenAI: установите OPENAI_API_KEY
   - Для Ollama: запустите `ollama serve`

## Возможные проблемы
- **Gradle wrapper не работает**: используйте установленный Gradle
- **Ошибки компиляции**: проверьте совместимость версий Java и Minecraft
- **LLM не отвечает**: проверьте настройки API ключей