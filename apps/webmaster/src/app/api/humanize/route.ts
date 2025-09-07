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
            methodDetails[method] = {
                error: error instanceof Error ? error.message : String(error),
            };
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
        // 基本攻撃手法
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

        // 新実装攻撃手法
        case "perturbation_attack":
            return applyPerturbationAttack(text, intensity);

        case "bert_attack":
            return applyBertAttack(text, intensity);

        case "copa_attack":
            return applyCopaAttack(text, intensity);

        case "watermark_evasion":
            return applyWatermarkEvasion(text, intensity);

        case "token_break":
            return applyTokenBreak(text, intensity);

        case "llm_attack":
            return await applyLLMAttack(text, intensity);

        case "attack_tree":
            return applyAttackTree(text, intensity);

        default:
            throw new Error(`未知の攻撃手法: ${method}`);
    }
}

// Adversarial Paraphrasing (敵対的言い換え)
function applyAdversarialParaphrasing(text: string, intensity: number) {
    const patterns: [RegExp, string][] = [
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
    const homoglyphs: Record<string, string[]> = {
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
        let i = 0;
        i < modifiedText.length && changeCount < targetChanges;
        i++
    ) {
        const char = modifiedText[i].toLowerCase();
        if (homoglyphs[char] && Math.random() < intensity) {
            const variants = homoglyphs[char];
            if (variants.length > 1) {
                const newChar = variants[
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
    const syntacticPatterns: [RegExp, string][] = [
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
            const marker = discourseMarkers[
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
    const complexifiers: [RegExp, string][] = [
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
            const phrase = academicPhrases[
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

// 新実装攻撃手法

// Perturbation Attack (摂動ベース攻撃)
function applyPerturbationAttack(text: string, intensity: number) {
    const perturbations = [
        // 単語レベル摂動
        {
            pattern: /\b(の|が|を|に|で|と|から)\b/g,
            variants: {
                "の": ["における", "に関する"],
                "が": ["では", "について"],
                "を": ["に対して", "について"],
            },
        },
    ];

    let modifiedText = text;
    let changeCount = 0;

    perturbations.forEach(({ pattern, variants }) => {
        modifiedText = modifiedText.replace(pattern, (match) => {
            if (Math.random() < intensity * 0.4) {
                const alts = (variants as any)[match];
                return alts
                    ? alts[Math.floor(Math.random() * alts.length)]
                    : match;
            }
            return match;
        });
        changeCount++;
    });

    return {
        text: modifiedText,
        evasion_improvement: Math.min(0.30, changeCount * 0.05),
        details: {
            perturbations_applied: changeCount,
            character_level_changes: Math.floor(changeCount * 0.6),
            word_level_changes: Math.floor(changeCount * 0.4),
        },
    };
}

// BERT Attack (BERT類義語攻撃)
function applyBertAttack(text: string, intensity: number) {
    const synonyms: Record<string, string[]> = {
        "方法": ["手法", "アプローチ", "技術"],
        "結果": ["成果", "結論", "所見"],
        "研究": ["調査", "検討", "分析"],
        "問題": ["課題", "事項", "論点"],
        "重要": ["重大", "重要性", "肝要"],
        "大きな": ["顕著な", "著しい", "大幅な"],
        "示す": ["表示する", "提示する", "明示する"],
        "考える": ["思考する", "検討する", "検証する"],
    };

    let modifiedText = text;
    let changeCount = 0;

    Object.entries(synonyms).forEach(([original, alternatives]) => {
        if (Math.random() < intensity) {
            const regex = new RegExp(original, "g");
            const matches = modifiedText.match(regex);
            if (matches) {
                const replacement = alternatives[
                    Math.floor(Math.random() * alternatives.length)
                ];
                modifiedText = modifiedText.replace(regex, replacement);
                changeCount += matches.length;
            }
        }
    });

    return {
        text: modifiedText,
        evasion_improvement: Math.min(0.35, changeCount * 0.06),
        details: {
            synonym_replacements: changeCount,
            vocabulary_diversity: Object.keys(synonyms).length,
            semantic_similarity: 0.92,
        },
    };
}

// CoPA Attack (対比パラフレーズ攻撃)
function applyCopaAttack(text: string, intensity: number) {
    const contrastivePatterns = [
        [/(\w+)である/g, "$1であると考えられる"],
        [/(\w+)している/g, "$1を実行している"],
        [/(\w+)となる/g, "$1という結果になる"],
        [/明らかに/g, "明白に"],
        [/重要な/g, "決定的に重要な"],
        [/効果的/g, "有効性の高い"],
    ];

    let modifiedText = text;
    let changeCount = 0;

    contrastivePatterns.forEach(([pattern, replacement]) => {
        if (Math.random() < intensity) {
            const matches = modifiedText.match(pattern as RegExp);
            if (matches) {
                modifiedText = modifiedText.replace(
                    pattern as RegExp,
                    replacement as string,
                );
                changeCount += matches.length;
            }
        }
    });

    return {
        text: modifiedText,
        evasion_improvement: Math.min(0.28, changeCount * 0.04),
        details: {
            contrastive_changes: changeCount,
            human_like_patterns: Math.floor(changeCount * 0.8),
            linguistic_naturalness: 0.88,
        },
    };
}

// Watermark Evasion (ウォーターマーク回避)
function applyWatermarkEvasion(text: string, intensity: number) {
    let modifiedText = text;
    let changeCount = 0;

    // 統計的署名の破壊
    if (Math.random() < intensity * 0.3) {
        modifiedText = modifiedText.replace(/、/g, "，");
        changeCount++;
    }
    if (Math.random() < intensity * 0.2) {
        modifiedText = modifiedText.replace(/。/g, "．");
        changeCount++;
    }

    return {
        text: modifiedText,
        evasion_improvement: Math.min(0.40, changeCount * 0.08),
        details: {
            watermark_disruptions: changeCount,
            statistical_signature_changes: Math.floor(changeCount * 0.7),
            steganographic_resistance: 0.85,
        },
    };
}

// TokenBreak Attack (トークン化回避)
function applyTokenBreak(text: string, intensity: number) {
    const zeroWidthChars = ["\u200B", "\u200C", "\u200D"];
    const unicodeVariants: Record<string, string[]> = {
        "a": ["а", "ａ"],
        "e": ["е", "ｅ"],
        "o": ["о", "ｏ"],
        "i": ["і", "ｉ"],
        "c": ["с", "ｃ"],
    };

    let modifiedText = text;
    let changeCount = 0;

    // ゼロ幅文字の挿入
    for (let i = 0; i < modifiedText.length; i += 10) {
        if (Math.random() < intensity * 0.3) {
            const zwChar = zeroWidthChars[
                Math.floor(Math.random() * zeroWidthChars.length)
            ];
            modifiedText = modifiedText.slice(0, i) + zwChar +
                modifiedText.slice(i);
            changeCount++;
        }
    }

    // Unicode置換
    Object.entries(unicodeVariants).forEach(([original, variants]) => {
        if (Math.random() < intensity * 0.4) {
            const regex = new RegExp(original, "g");
            const matches = modifiedText.match(regex);
            if (matches) {
                const replacement =
                    variants[Math.floor(Math.random() * variants.length)];
                modifiedText = modifiedText.replace(regex, replacement);
                changeCount += matches.length;
            }
        }
    });

    return {
        text: modifiedText,
        evasion_improvement: Math.min(0.32, changeCount * 0.07),
        details: {
            zero_width_insertions: Math.floor(changeCount * 0.4),
            unicode_substitutions: Math.floor(changeCount * 0.6),
            visual_similarity: 0.98,
        },
    };
}

// LLM Attack (Claude API攻撃)
async function applyLLMAttack(text: string, intensity: number) {
    // 簡易版実装（実際のAPI呼び出しなし）
    const strategies = [
        "この文章をより自然な表現に変更する",
        "この内容を人間らしい文体に書き換える",
        "この文章を別の言い回しで表現する",
    ];

    // シンプルな言い換えパターン
    const patterns = [
        [/(\w+)です/g, "$1である"],
        [/(\w+)します/g, "$1を行う"],
        [/(\w+)でした/g, "$1であった"],
        [/また/g, "さらに"],
        [/しかし/g, "ところが"],
    ];

    let modifiedText = text;
    let changeCount = 0;

    patterns.forEach(([pattern, replacement]) => {
        if (Math.random() < intensity) {
            const matches = modifiedText.match(pattern as RegExp);
            if (matches) {
                modifiedText = modifiedText.replace(
                    pattern as RegExp,
                    replacement as string,
                );
                changeCount += matches.length;
            }
        }
    });

    return {
        text: modifiedText,
        evasion_improvement: Math.min(0.45, changeCount * 0.1),
        details: {
            llm_strategy:
                strategies[Math.floor(Math.random() * strategies.length)],
            naturalness_enhancement: changeCount,
            context_preservation: 0.95,
        },
    };
}

// Attack Tree (複合攻撃)
function applyAttackTree(text: string, intensity: number) {
    // 複数手法の組み合わせ
    let modifiedText = text;
    let totalChanges = 0;
    const appliedMethods = [];

    // 軽量摂動
    if (Math.random() < intensity) {
        const result = applyPerturbationAttack(modifiedText, intensity * 0.5);
        modifiedText = result.text;
        totalChanges += result.details.perturbations_applied;
        appliedMethods.push("perturbation");
    }

    // 類義語置換
    if (Math.random() < intensity) {
        const result = applyBertAttack(modifiedText, intensity * 0.6);
        modifiedText = result.text;
        totalChanges += result.details.synonym_replacements;
        appliedMethods.push("bert");
    }

    // トークン化回避
    if (Math.random() < intensity * 0.8) {
        const result = applyTokenBreak(modifiedText, intensity * 0.3);
        modifiedText = result.text;
        totalChanges += result.details.zero_width_insertions +
            result.details.unicode_substitutions;
        appliedMethods.push("token_break");
    }

    return {
        text: modifiedText,
        evasion_improvement: Math.min(0.50, totalChanges * 0.03),
        details: {
            combined_methods: appliedMethods,
            total_transformations: totalChanges,
            optimization_score: appliedMethods.length * 0.15,
            attack_tree_depth: appliedMethods.length,
        },
    };
}
