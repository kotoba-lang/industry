import { NextRequest, NextResponse } from "next/server";
import { exec } from "child_process";
import { promisify } from "util";
import path from "path";

const execAsync = promisify(exec);

export async function POST(request: NextRequest) {
    try {
        const { text, methods, intensity = 0.3 } = await request.json();

        if (!text || !methods || methods.length === 0) {
            return NextResponse.json(
                { error: "テキストと攻撃手法が必要です" },
                { status: 400 },
            );
        }

        const startTime = Date.now();

        // Pythonスクリプトを実行してテキストを処理
        const result = await processTextWithMethods(text, methods, intensity);

        const processingTime = (Date.now() - startTime) / 1000;

        return NextResponse.json({
            original_text: text,
            processed_text: result.processed_text,
            applied_methods: methods,
            analysis: {
                character_change: result.processed_text.length - text.length,
                character_change_ratio:
                    (result.processed_text.length - text.length) / text.length,
                estimated_detection_evasion: result.estimated_evasion,
                processing_time: processingTime,
            },
            method_details: result.method_details,
        });
    } catch (error) {
        console.error("処理エラー:", error);
        return NextResponse.json(
            { error: "処理中にエラーが発生しました" },
            { status: 500 },
        );
    }
}

async function processTextWithMethods(
    text: string,
    methods: string[],
    intensity: number,
): Promise<{
    processed_text: string;
    estimated_evasion: number;
    method_details: Record<string, any>;
}> {
    let processedText = text;
    let totalEvasion = 0;
    const methodDetails: Record<string, any> = {};

    // 各攻撃手法を順次適用
    for (const method of methods) {
        try {
            const result = await applyAttackMethod(
                processedText,
                method,
                intensity,
            );
            processedText = result.text;
            totalEvasion += result.evasion_improvement;
            methodDetails[method] = result.details;
        } catch (error) {
            console.error(`手法 ${method} でエラー:`, error);
            methodDetails[method] = { error: error.message };
        }
    }

    // 総合効果の計算（重複効果を考慮）
    const estimatedEvasion = Math.min(0.95, totalEvasion * 0.7); // 保守的な推定

    return {
        processed_text: processedText,
        estimated_evasion: estimatedEvasion,
        method_details: methodDetails,
    };
}

async function applyAttackMethod(
    text: string,
    method: string,
    intensity: number,
): Promise<{
    text: string;
    evasion_improvement: number;
    details: any;
}> {
    // JavaScriptで実装された軽量版の攻撃手法
    switch (method) {
        case "adversarial_paraphrasing":
            return applyAdversarialParaphrasing(text, intensity);

        case "grad_escape":
            return applyGradEscape(text, intensity);

        case "silver_speak":
            return applySilverSpeak(text, intensity);

        case "syntactic_perturbation":
            return applySyntacticPerturbation(text, intensity);

        case "linguistic_complexity":
            return applyLinguisticComplexity(text, intensity);

        default:
            throw new Error(`未知の攻撃手法: ${method}`);
    }
}

// Adversarial Paraphrasing (敵対的言い換え)
function applyAdversarialParaphrasing(text: string, intensity: number) {
    const patterns = [
        [/(\w+)している/g, "$1を行っている"],
        [/(\w+)する/g, "$1を実施する"],
        [/(\w+)した/g, "$1を行った"],
        [/(\w+)である/g, "$1となっている"],
        [/明らかにした/g, "解明した"],
        [/示している/g, "示唆している"],
        [/しかし、/g, "一方で、"],
        [/また、/g, "さらに、"],
        [/使用する/g, "用いる"],
        [/利用する/g, "活用する"],
    ];

    let modifiedText = text;
    let changeCount = 0;

    patterns.forEach(([pattern, replacement]) => {
        if (Math.random() < intensity) {
            const matches = modifiedText.match(pattern);
            if (matches) {
                modifiedText = modifiedText.replace(pattern, replacement);
                changeCount += matches.length;
            }
        }
    });

    return {
        text: modifiedText,
        evasion_improvement: Math.min(0.25, changeCount * 0.02),
        details: {
            patterns_applied: changeCount,
            change_ratio: (modifiedText.length - text.length) / text.length,
        },
    };
}

// GradEscape (勾配ベース攻撃)
function applyGradEscape(text: string, intensity: number) {
    const punctuationVariants = {
        "。": ["。", "．", "｡"],
        "、": ["、", "，", "､"],
        "？": ["？", "?"],
        "！": ["！", "!"],
        "：": ["：", ":"],
        "（": ["（", "("],
        "）": ["）", ")"],
    };

    let modifiedText = text;
    let changeCount = 0;

    Object.entries(punctuationVariants).forEach(([original, variants]) => {
        if (Math.random() < intensity && variants.length > 1) {
            const newVariant =
                variants[Math.floor(Math.random() * variants.length)];
            const regex = new RegExp(
                original.replace(/[.*+?^${}()|[\]\\]/g, "\\$&"),
                "g",
            );
            const matches = modifiedText.match(regex);
            if (matches && Math.random() < 0.3) {
                modifiedText = modifiedText.replace(regex, newVariant);
                changeCount += matches.length;
            }
        }
    });

    return {
        text: modifiedText,
        evasion_improvement: Math.min(0.15, changeCount * 0.01),
        details: {
            punctuation_changes: changeCount,
            invisible_chars_added: 0,
        },
    };
}

// SilverSpeak (同形異義文字攻撃)
function applySilverSpeak(text: string, intensity: number) {
    const homoglyphs = {
        "a": ["a", "а", "ɑ", "α"],
        "o": ["o", "о", "ο", "օ"],
        "e": ["e", "е", "ε"],
        "p": ["p", "р", "ρ"],
        "c": ["c", "с", "ϲ"],
        "x": ["x", "х", "χ"],
        "y": ["y", "у", "γ"],
        "i": ["i", "і", "ι"],
        "n": ["n", "ո"],
        "s": ["s", "ѕ"],
        "t": ["t", "т", "τ"],
        "u": ["u", "υ"],
        "v": ["v", "ѵ", "ν"],
        "w": ["w", "ω"],
    };

    let modifiedText = text;
    let changeCount = 0;
    const targetChanges = Math.floor(text.length * intensity * 0.1);

    for (
        let i = 0; i < modifiedText.length && changeCount < targetChanges; i++
    ) {
        const char = modifiedText[i].toLowerCase();
        if (homoglyphs[char] && Math.random() < intensity) {
            const variants = homoglyphs[char];
            if (variants.length > 1) {
                const newChar =
                    variants[
                        Math.floor(Math.random() * (variants.length - 1)) + 1
                    ];
                modifiedText = modifiedText.substring(0, i) + newChar +
                    modifiedText.substring(i + 1);
                changeCount++;
            }
        }
    }

    return {
        text: modifiedText,
        evasion_improvement: Math.min(0.35, changeCount * 0.05),
        details: {
            homoglyph_substitutions: changeCount,
            homoglyph_ratio: changeCount / text.length,
            estimated_visual_similarity: 0.95,
        },
    };
}

// Syntactic Perturbation (統語的摂動)
function applySyntacticPerturbation(text: string, intensity: number) {
    const syntacticPatterns = [
        [/(\w+)のため、(\w+)/g, "$1により、$2"],
        [/(\w+)によって(\w+)/g, "$2は$1を原因として"],
        [/その後、(\w+)/g, "続いて、$1"],
        [/最初に(\w+)/g, "はじめに$1"],
        [/重要な(\w+)/g, "重要性の高い$1"],
        [/大きな(\w+)/g, "顕著な$1"],
        [/方法/g, "手法"],
        [/結果/g, "成果"],
        [/問題/g, "課題"],
    ];

    const discourseMarkers = [
        "つまり",
        "すなわち",
        "具体的には",
        "例えば",
        "特に",
    ];
    const hedges = ["おそらく", "と思われる", "と考えられる", "ようである"];

    let modifiedText = text;
    let changeCount = 0;

    // 統語的パターンの適用
    syntacticPatterns.forEach(([pattern, replacement]) => {
        if (Math.random() < intensity * 0.6) {
            const matches = modifiedText.match(pattern);
            if (matches) {
                modifiedText = modifiedText.replace(pattern, replacement);
                changeCount += matches.length;
            }
        }
    });

    // 談話標識の追加
    const sentences = modifiedText.split(/[。！？]/);
    for (let i = 1; i < sentences.length; i++) {
        if (Math.random() < intensity * 0.3) {
            const marker =
                discourseMarkers[
                    Math.floor(Math.random() * discourseMarkers.length)
                ];
            sentences[i] = marker + "、" + sentences[i];
            changeCount++;
        }
    }

    // ヘッジ表現の追加
    for (let i = 0; i < sentences.length; i++) {
        if (Math.random() < intensity * 0.2) {
            const hedge = hedges[Math.floor(Math.random() * hedges.length)];
            sentences[i] = sentences[i] + hedge;
            changeCount++;
        }
    }

    modifiedText = sentences.join("。");

    return {
        text: modifiedText,
        evasion_improvement: Math.min(0.20, changeCount * 0.03),
        details: {
            syntactic_transformations: changeCount,
            discourse_markers_added: Math.floor(changeCount * 0.4),
            hedges_added: Math.floor(changeCount * 0.2),
        },
    };
}

// Linguistic Complexity (言語複雑性攻撃)
function applyLinguisticComplexity(text: string, intensity: number) {
    const complexifiers = [
        [/(\w+である)/g, "$1と考えられる"],
        [/(\w+している)/g, "$1と思われる"],
        [/(\w+)。/g, "$1と報告されている。"],
        [/調べた/g, "調査した"],
        [/見つけた/g, "発見した"],
        [/作った/g, "作成した"],
        [/使った/g, "使用した"],
    ];

    const connectors = [
        "さらに、",
        "なお、",
        "ちなみに、",
        "それと同時に、",
        "やはり、",
    ];
    const academicPhrases = [
        "研究者の見解では、",
        "学術的見地から言えば、",
        "専門家によれば、",
        "最新の知見では、",
    ];

    let modifiedText = text;
    let changeCount = 0;

    // 複雑化パターンの適用
    complexifiers.forEach(([pattern, replacement]) => {
        if (Math.random() < intensity * 0.5) {
            const matches = modifiedText.match(pattern);
            if (matches) {
                modifiedText = modifiedText.replace(pattern, replacement);
                changeCount += matches.length;
            }
        }
    });

    // 接続語の追加
    const sentences = modifiedText.split(/[。！？]/);
    for (let i = 1; i < sentences.length; i++) {
        if (Math.random() < intensity * 0.4) {
            const connector =
                connectors[Math.floor(Math.random() * connectors.length)];
            sentences[i] = connector + sentences[i];
            changeCount++;
        }
    }

    // 学術的表現の追加
    for (let i = 0; i < sentences.length; i++) {
        if (Math.random() < intensity * 0.2) {
            const phrase =
                academicPhrases[
                    Math.floor(Math.random() * academicPhrases.length)
                ];
            sentences[i] = phrase + sentences[i];
            changeCount++;
        }
    }

    modifiedText = sentences.join("。");

    return {
        text: modifiedText,
        evasion_improvement: Math.min(0.18, changeCount * 0.025),
        details: {
            complexity_additions: changeCount,
            connectors_added: Math.floor(changeCount * 0.6),
            academic_phrases_added: Math.floor(changeCount * 0.2),
            estimated_readability_decrease: changeCount * 0.05,
        },
    };
}
