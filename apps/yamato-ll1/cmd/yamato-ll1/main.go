package main

import (
	"bufio"
	"fmt"
	"io"
	"os"

	"github.com/junkawasaki/yamato-ll1/internal/lexer"
	"github.com/junkawasaki/yamato-ll1/internal/parser"
)

func main() {
	fmt.Println("Yamato LL(1) Parser - REPL")
	start(os.Stdin, os.Stdout)
}

func start(in io.Reader, out io.Writer) {
	scanner := bufio.NewScanner(in)

	for {
		fmt.Fprint(out, ">> ")
		scanned := scanner.Scan()
		if !scanned {
			return
		}

		line := scanner.Text()
		l := lexer.New(line)
		p := parser.New(l)

		sentence := p.ParseSentence()
		if len(p.Errors()) != 0 {
			printParserErrors(out, p.Errors())
			continue
		}

		io.WriteString(out, sentence.String()+"\n")
	}
}

func printParserErrors(out io.Writer, errors []string) {
	io.WriteString(out, " parser errors:\n")
	for _, msg := range errors {
		io.WriteString(out, "\t"+msg+"\n")
	}
}
