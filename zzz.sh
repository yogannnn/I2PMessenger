#!/bin/bash

OUTPUT="project_dump.txt"
> "$OUTPUT"

echo "Сканируем проект..." >&2

# Список расширений для включения (через -o)
# Используем find с группировкой: \( -name "*.kt" -o -name "*.java" ... \)
# И исключаем папки через ! -path

find . -type f \
    \( -name "*.kt" -o -name "*.kts" -o -name "*.java" \
       -o -name "*.xml" -o -name "*.json" -o -name "*.yaml" -o -name "*.yml" \
       -o -name "*.properties" -o -name "*.gradle" -o -name "*.gradle.kts" \
       -o -name "*.toml" -o -name "*.md" -o -name "*.txt" \
       -o -name "*.sh" -o -name "*.bat" -o -name "*.cfg" -o -name "*.conf" \
       -o -name "*.ini" -o -name "*.html" -o -name "*.css" -o -name "*.js" \) \
    ! -path "*/build/*" \
    ! -path "*/.gradle/*" \
    ! -path "*/.idea/*" \
    ! -path "*/.vscode/*" \
    ! -path "*/.git/*" \
    ! -path "*/out/*" \
    ! -path "*/target/*" \
    ! -path "*/logs/*" \
    ! -path "*/tmp/*" \
    ! -path "*/temp/*" \
    -print0 | while IFS= read -r -d '' file; do
        rel_path="${file#./}"
        echo "===== FILE: $rel_path =====" >> "$OUTPUT"
        cat "$file" >> "$OUTPUT"
        echo -e "\n\n" >> "$OUTPUT"
    done

echo "Готово! Содержимое в $OUTPUT (размер: $(du -h "$OUTPUT" | cut -f1))" >&2
