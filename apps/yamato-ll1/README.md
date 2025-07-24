# ACJ - yamato-ll1

This repository contains the source code for `yamato-ll1`, a recursive-descent LL(1) parser for Attempto Controlled Japanese (ACJ). ACJ is a subset of modern Japanese with a precisely defined and computable syntax.

This implementation is the subject of the paper *"Attempto Controlled Japanese (ACJ): A Case Study with the yamato-ll1 Parser"*.

## Grammar
The parser implements the following LL(1) grammar for ACJ.

### Modern Japanese Grammar
```antlr
grammar ModernJapanese;

options { k=1; }

// --- Top Level ---
sentence
  : clause+ EOF
  ;

// --- Clause and Phrase Structure ---
clause
  : np vp
  ;

np
  : baseNP npTail
  ;

npTail
  : relClause npTail
  | /* ε */
  ;

baseNP
  : NOUN caseParticle?
  ;

relClause
  : adjPhrase
  | clause
  ;

// --- Verb Phrase and Auxiliaries ---
vp
  : (np)? (verbPhrase | copulaPhrase)
  ;

verbPhrase
  : VERB auxList
  ;

copulaPhrase
  : (NOUN | ADJ) COPULA
  ;

auxList
  : AUX auxList
  | /* ε */
  ;

// --- Adjective Phrase ---
adjPhrase
  : ADJ
  ;

// --- Morphemes ---
caseParticle
  : 'が' | 'を' | 'に' | 'は' | 'の'
  ;

AUX
  : 'き' | 'む' | 'ず' // Classical
  | 'ます' | 'た'      // Modern
  ;

COPULA
  : 'です'
  ;

NOUN      : [ぁ-ん一-龥]+ ;
VERB      : [ぁ-ん一-龥]+ ;
ADJ       : [ぁ-ん一-龥]+ ;
```
(For details on the evolution from the Classical Japanese grammar, please refer to the paper.)

## Getting Started

### Prerequisites
- Go 1.22 or later

### Installation
Clone the repository:
```bash
git clone https://github.com/jun784/researches.git
cd researches
```

### Usage
You can run the parser as a REPL (Read-Eval-Print Loop):
```bash
go run ./cmd/yamato-ll1
```
The REPL will start. Enter a space-separated Japanese sentence that conforms to the ACJ grammar.
```
>> 私 は 学生 です
( (私 は) (学生 です) )
>> 
```

## Citing
If you use this work, please cite our paper:
> J. Kawasaki. (2024). *Attempto Controlled Japanese (ACJ): A Case Study with the yamato-ll1 Parser*. arXiv:[TODO: Add arXiv ID after submission].

## License
This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.