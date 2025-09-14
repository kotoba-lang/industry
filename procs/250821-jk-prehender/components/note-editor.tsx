"use client"

import { useState, useEffect, useRef, useCallback } from "react"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Textarea } from "@/components/ui/textarea"
import { Badge } from "@/components/ui/badge"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Separator } from "@/components/ui/separator"
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from "@/components/ui/tooltip"
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover"
import { Command, CommandEmpty, CommandGroup, CommandInput, CommandItem, CommandList } from "@/components/ui/command"
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs"
import { ScrollArea } from "@/components/ui/scroll-area"
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogTrigger } from "@/components/ui/dialog"
import { Label } from "@/components/ui/label"
import { Switch } from "@/components/ui/switch"
import { Slider } from "@/components/ui/slider"
import {
  ImageIcon, Bold, Italic, List, Link, Hash, X, GitBranch, ArrowRight, ExternalLink, AtSign,
  Type, Heading1, Heading2, Heading3, Quote, Code, Minus, Undo, Redo, Save, Eye,
  Settings, Palette, Zap, Sparkles, FileText, Users, BookOpen, Lightbulb,
  CheckSquare, Calendar, FolderOpen, Copy, Tag, Plus, Minus as MinusIcon
} from "lucide-react"
import { extractTitleFromContent } from "@/lib/utils"
import type { Note, ReferenceType, StreamReference } from "@/types/note"

interface NoteEditorProps {
  note: Note | null
  onUpdateNote: (id: string, updates: Partial<Note>) => void
  onNewNote: () => void
  allNotes: Note[]
  onAddStreamReference?: (fromStreamId: string, toStreamId: string, referenceType: ReferenceType, context?: string) => void
  onRemoveStreamReference?: (fromStreamId: string, referenceId: string) => void
}

const REFERENCE_TYPE_LABELS: Record<ReferenceType, { label: string; color: string; shortCode: string }> = {
  'relates_to': { label: '関連', color: 'bg-blue-100 text-blue-800', shortCode: '関連' },
  'depends_on': { label: '依存', color: 'bg-red-100 text-red-800', shortCode: '依存' },
  'follows_from': { label: '続き', color: 'bg-green-100 text-green-800', shortCode: '続き' },
  'contradicts': { label: '矛盾', color: 'bg-orange-100 text-orange-800', shortCode: '矛盾' },
  'supports': { label: '支持', color: 'bg-teal-100 text-teal-800', shortCode: '支持' },
  'questions': { label: '疑問', color: 'bg-yellow-100 text-yellow-800', shortCode: '疑問' },
  'answers': { label: '回答', color: 'bg-purple-100 text-purple-800', shortCode: '回答' },
  'quotes': { label: '引用', color: 'bg-gray-100 text-gray-800', shortCode: '引用' },
  'extends': { label: '拡張', color: 'bg-indigo-100 text-indigo-800', shortCode: '拡張' }
}

interface StreamReferenceMatch {
  match: string
  referenceType: ReferenceType
  streamTitle: string
  startIndex: number
  endIndex: number
}

interface AutocompleteState {
  show: boolean
  position: { top: number; left: number }
  query: string
  referenceType: ReferenceType | null
  cursorPosition: number
}

export function NoteEditor({ 
  note, 
  onUpdateNote, 
  onNewNote, 
  allNotes,
  onAddStreamReference,
  onRemoveStreamReference 
}: NoteEditorProps) {
  const [content, setContent] = useState("")
  const [newTag, setNewTag] = useState("")
  const [newReferenceTarget, setNewReferenceTarget] = useState("")
  const [newReferenceType, setNewReferenceType] = useState<ReferenceType>("relates_to")
  const [newReferenceContext, setNewReferenceContext] = useState("")
  const [autocomplete, setAutocomplete] = useState<AutocompleteState>({
    show: false,
    position: { top: 0, left: 0 },
    query: "",
    referenceType: null,
    cursorPosition: 0
  })
  const textareaRef = useRef<HTMLTextAreaElement>(null)
  const measureRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (note) {
      setContent(note.content)
    } else {
      setContent("")
    }
  }, [note])

  // ストリーム参照のパターンを解析
  const parseStreamReferences = useCallback((text: string): StreamReferenceMatch[] => {
    const pattern = /@\[([^:]+):([^\]]+)\]/g
    const matches: StreamReferenceMatch[] = []
    let match

    while ((match = pattern.exec(text)) !== null) {
      const referenceTypeLabel = match[1]
      const streamTitle = match[2]
      
      // 参照タイプを逆引き
      const referenceType = Object.entries(REFERENCE_TYPE_LABELS).find(
        ([_, value]) => value.shortCode === referenceTypeLabel
      )?.[0] as ReferenceType

      if (referenceType) {
        matches.push({
          match: match[0],
          referenceType,
          streamTitle,
          startIndex: match.index,
          endIndex: match.index + match[0].length
        })
      }
    }

    return matches
  }, [])

  // テキスト内のストリーム参照を実際の参照に変換
  const syncEmbeddedReferences = useCallback(async (text: string) => {
    if (!note) return

    const embeddedRefs = parseStreamReferences(text)
    const currentRefs = [...note.references]

    // 既存の埋め込み参照を削除（通常の参照セクションで追加されたものは保持）
    const nonEmbeddedRefs = currentRefs.filter(ref => 
      !ref.context?.startsWith('embedded:')
    )

    // 新しい埋め込み参照を追加
    for (const embeddedRef of embeddedRefs) {
      const targetStream = allNotes.find(n => 
        extractTitleFromContent(n.content).toLowerCase() === embeddedRef.streamTitle.toLowerCase()
      )

      if (targetStream) {
        const existingRef = nonEmbeddedRefs.find(ref => 
          ref.targetStreamId === targetStream.streamId &&
          ref.referenceType === embeddedRef.referenceType &&
          ref.context?.startsWith('embedded:')
        )

        if (!existingRef) {
          const newReference: StreamReference = {
            id: `embedded-${Date.now()}-${Math.random()}`,
            targetStreamId: targetStream.streamId,
            referenceType: embeddedRef.referenceType,
            context: `embedded:${embeddedRef.streamTitle}`,
            createdAt: new Date(),
            isActive: true
          }
          nonEmbeddedRefs.push(newReference)
        }
      }
    }

    if (nonEmbeddedRefs.length !== currentRefs.length || 
        !nonEmbeddedRefs.every((ref, i) => currentRefs[i] && ref.id === currentRefs[i].id)) {
      onUpdateNote(note.id, { references: nonEmbeddedRefs })
    }
  }, [note, allNotes, onUpdateNote])

  const handleContentChange = (newContent: string) => {
    setContent(newContent)
    if (note) {
      // 最初の1行目をタイトルとして抽出
      const title = extractTitleFromContent(newContent)
      onUpdateNote(note.id, { 
        content: newContent, 
        title: title,
        updatedAt: new Date() 
      })

      // 埋め込み参照を同期
      syncEmbeddedReferences(newContent)
    }
  }

  // カーソル位置を取得してオートコンプリートの位置を計算
  const getCursorPosition = useCallback((cursorIndex: number) => {
    if (!textareaRef.current || !measureRef.current) return { top: 0, left: 0 }

    const textarea = textareaRef.current
    const measure = measureRef.current
    
    // カーソル位置までのテキストをmeasure要素にコピー
    const textBeforeCursor = content.substring(0, cursorIndex)
    measure.textContent = textBeforeCursor
    
    // 最後の文字の位置を取得
    const range = document.createRange()
    const lastTextNode = measure.lastChild
    if (lastTextNode) {
      range.setStart(lastTextNode, lastTextNode.textContent?.length || 0)
      range.setEnd(lastTextNode, lastTextNode.textContent?.length || 0)
      const rect = range.getBoundingClientRect()
      const textareaRect = textarea.getBoundingClientRect()
      
      return {
        top: rect.top - textareaRect.top + 25,
        left: rect.left - textareaRect.left
      }
    }
    
    return { top: 20, left: 0 }
  }, [content])

  // オートコンプリートのトリガー検出
  const handleTextareaChange = (e: React.ChangeEvent<HTMLTextAreaElement>) => {
    const newContent = e.target.value
    const cursorPosition = e.target.selectionStart
    
    // @[ の入力を検出
    const textBeforeCursor = newContent.substring(0, cursorPosition)
    const lastAtBracket = textBeforeCursor.lastIndexOf('@[')
    
    if (lastAtBracket !== -1) {
      const textAfterAtBracket = textBeforeCursor.substring(lastAtBracket + 2)
      
      // まだ閉じ括弧がない場合
      if (!textAfterAtBracket.includes(']')) {
        const colonIndex = textAfterAtBracket.indexOf(':')
        
        if (colonIndex === -1) {
          // 参照タイプを入力中
          const query = textAfterAtBracket
          const position = getCursorPosition(cursorPosition)
          
          setAutocomplete({
            show: true,
            position,
            query,
            referenceType: null,
            cursorPosition: lastAtBracket
          })
        } else {
          // ストリーム名を入力中
          const referenceTypeText = textAfterAtBracket.substring(0, colonIndex)
          const streamQuery = textAfterAtBracket.substring(colonIndex + 1)
          
          const referenceType = Object.entries(REFERENCE_TYPE_LABELS).find(
            ([_, value]) => value.shortCode === referenceTypeText
          )?.[0] as ReferenceType
          
          if (referenceType) {
            const position = getCursorPosition(cursorPosition)
            
            setAutocomplete({
              show: true,
              position,
              query: streamQuery,
              referenceType,
              cursorPosition: lastAtBracket
            })
          }
        }
      } else {
        setAutocomplete(prev => ({ ...prev, show: false }))
      }
    } else {
      setAutocomplete(prev => ({ ...prev, show: false }))
    }
    
    handleContentChange(newContent)
  }

  // オートコンプリート選択
  const handleAutocompleteSelect = (value: string) => {
    if (!textareaRef.current) return

    const textarea = textareaRef.current
    const cursorPosition = autocomplete.cursorPosition
    const textBeforeCursor = content.substring(0, cursorPosition)
    const textAfterCursor = content.substring(textarea.selectionStart)
    
    let replacement = ""
    
    if (autocomplete.referenceType === null) {
      // 参照タイプを選択
      replacement = `@[${value}:`
    } else {
      // ストリーム名を選択
      const referenceTypeLabel = REFERENCE_TYPE_LABELS[autocomplete.referenceType].shortCode
      replacement = `@[${referenceTypeLabel}:${value}]`
    }
    
    const beforeAtBracket = textBeforeCursor.substring(0, textBeforeCursor.lastIndexOf('@['))
    const newContent = beforeAtBracket + replacement + textAfterCursor
    
    handleContentChange(newContent)
    
    // カーソル位置を調整
    setTimeout(() => {
      const newCursorPosition = beforeAtBracket.length + replacement.length
      textarea.focus()
      textarea.setSelectionRange(newCursorPosition, newCursorPosition)
    }, 0)
    
    setAutocomplete(prev => ({ ...prev, show: false }))
  }

  // 他のストリームから選択可能なオプション
  const availableStreams = allNotes.filter(n => n.streamId !== note?.streamId)

  // オートコンプリート候補の生成
  const getAutocompleteSuggestions = () => {
    if (autocomplete.referenceType === null) {
      // 参照タイプの候補
      return Object.values(REFERENCE_TYPE_LABELS)
        .filter(type => type.shortCode.toLowerCase().includes(autocomplete.query.toLowerCase()))
        .map(type => ({ label: type.shortCode, value: type.shortCode }))
    } else {
      // ストリーム名の候補
      return availableStreams
        .filter(stream => 
          extractTitleFromContent(stream.content).toLowerCase()
            .includes(autocomplete.query.toLowerCase())
        )
        .map(stream => ({
          label: extractTitleFromContent(stream.content),
          value: extractTitleFromContent(stream.content),
          stream
        }))
    }
  }

  const addTag = () => {
    if (newTag && note && !note.tags.includes(newTag)) {
      onUpdateNote(note.id, {
        tags: [...note.tags, newTag],
        updatedAt: new Date(),
      })
      setNewTag("")
    }
  }

  const removeTag = (tagToRemove: string) => {
    if (note) {
      onUpdateNote(note.id, {
        tags: note.tags.filter((tag) => tag !== tagToRemove),
        updatedAt: new Date(),
      })
    }
  }

  const addStreamReference = () => {
    if (newReferenceTarget && note && onAddStreamReference) {
      onAddStreamReference(
        note.streamId, 
        newReferenceTarget, 
        newReferenceType, 
        newReferenceContext || undefined
      )
      setNewReferenceTarget("")
      setNewReferenceContext("")
    }
  }

  const removeStreamReference = (referenceId: string) => {
    if (note && onRemoveStreamReference) {
      onRemoveStreamReference(note.streamId, referenceId)
    }
  }

  const insertFormatting = (format: string) => {
    if (!textareaRef.current) return

    const textarea = textareaRef.current
    const start = textarea.selectionStart
    const end = textarea.selectionEnd
    const selectedText = content.substring(start, end)

    let newText = ""
    switch (format) {
      case "bold":
        newText = `**${selectedText}**`
        break
      case "italic":
        newText = `*${selectedText}*`
        break
      case "list":
        newText = `\n- ${selectedText}`
        break
      case "link":
        newText = `[${selectedText}](url)`
        break
      case "stream-ref":
        newText = `@[関連:${selectedText || 'ストリーム名'}]`
        break
      default:
        return
    }

    const newContent = content.substring(0, start) + newText + content.substring(end)
    handleContentChange(newContent)

    // Reset cursor position
    setTimeout(() => {
      textarea.focus()
      textarea.setSelectionRange(start + newText.length, start + newText.length)
    }, 0)
  }

  // テキスト内の埋め込み参照をハイライト表示するための処理
  const renderContentWithHighlights = () => {
    const embeddedRefs = parseStreamReferences(content)
    if (embeddedRefs.length === 0) return content

    let highlightedContent = content
    let offset = 0

    embeddedRefs.forEach((ref) => {
      const before = highlightedContent.substring(0, ref.startIndex + offset)
      const after = highlightedContent.substring(ref.endIndex + offset)
      const refInfo = REFERENCE_TYPE_LABELS[ref.referenceType]
      
      const highlighted = `<span class="inline-flex items-center px-2 py-1 rounded-md text-xs font-medium ${refInfo.color} cursor-pointer" data-stream-ref="${ref.streamTitle}">${ref.match}</span>`
      
      highlightedContent = before + highlighted + after
      offset += highlighted.length - ref.match.length
    })

    return highlightedContent
  }

  if (!note) {
    return (
      <TooltipProvider>
        <div className="flex-1 flex items-center justify-center bg-background/50">
          <div className="text-center max-w-md">
            <div className="w-20 h-20 bg-gradient-to-br from-primary/20 to-primary/10 rounded-full flex items-center justify-center mx-auto mb-6 border border-primary/20">
              <FileText className="w-10 h-10 text-primary" />
            </div>
            <h2 className="text-2xl font-bold text-foreground mb-3">No Stream Selected</h2>
            <p className="text-muted-foreground mb-8 leading-relaxed">
              Choose a stream from the sidebar or create a new one to start writing.
            </p>
            <div className="space-y-4">
              <Button
                onClick={onNewNote}
                size="lg"
                className="prehender-gradient hover:opacity-90 transition-all duration-200 transform hover:scale-105"
              >
                <Plus className="w-5 h-5 mr-2" />
                Create New Stream
              </Button>
              <div className="flex items-center justify-center gap-4 text-sm text-muted-foreground">
                <div className="flex items-center gap-1">
                  <kbd className="px-2 py-1 bg-muted rounded text-xs">⌘</kbd>
                  <span>+</span>
                  <kbd className="px-2 py-1 bg-muted rounded text-xs">N</kbd>
                </div>
                <span>to create quickly</span>
              </div>
            </div>
          </div>
        </div>
      </TooltipProvider>
    )
  }

  return (
    <TooltipProvider>
      <div className="flex-1 flex flex-col bg-background">
        {/* Hidden measuring element for cursor position calculation */}
        <div
          ref={measureRef}
          className="absolute -top-1000 -left-1000 whitespace-pre-wrap text-base font-mono pointer-events-none"
          style={{
            font: 'inherit',
            letterSpacing: 'inherit',
            wordSpacing: 'inherit',
          }}
        />

        {/* Enhanced Editor Toolbar */}
        <div className="border-b border-border bg-background/95 backdrop-blur supports-[backdrop-filter]:bg-background/60 px-4 py-3">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-1">
              <Tooltip>
                <TooltipTrigger asChild>
                  <Button variant="ghost" size="sm" onClick={() => insertFormatting("bold")}>
                    <Bold className="w-4 h-4" />
                  </Button>
                </TooltipTrigger>
                <TooltipContent>Bold (⌘B)</TooltipContent>
              </Tooltip>

              <Tooltip>
                <TooltipTrigger asChild>
                  <Button variant="ghost" size="sm" onClick={() => insertFormatting("italic")}>
                    <Italic className="w-4 h-4" />
                  </Button>
                </TooltipTrigger>
                <TooltipContent>Italic (⌘I)</TooltipContent>
              </Tooltip>

              <Tooltip>
                <TooltipTrigger asChild>
                  <Button variant="ghost" size="sm" onClick={() => insertFormatting("list")}>
                    <List className="w-4 h-4" />
                  </Button>
                </TooltipTrigger>
                <TooltipContent>List</TooltipContent>
              </Tooltip>

              <Tooltip>
                <TooltipTrigger asChild>
                  <Button variant="ghost" size="sm" onClick={() => insertFormatting("link")}>
                    <Link className="w-4 h-4" />
                  </Button>
                </TooltipTrigger>
                <TooltipContent>Link</TooltipContent>
              </Tooltip>

              <Separator orientation="vertical" className="h-6 mx-2" />

              <Tooltip>
                <TooltipTrigger asChild>
                  <Button variant="ghost" size="sm" onClick={() => insertFormatting("stream-ref")}>
                    <AtSign className="w-4 h-4" />
                  </Button>
                </TooltipTrigger>
                <TooltipContent>Stream Reference</TooltipContent>
              </Tooltip>

              <Tooltip>
                <TooltipTrigger asChild>
                  <Button variant="ghost" size="sm" onClick={() => console.log("Insert Image clicked")}>
                    <ImageIcon className="w-4 h-4" />
                  </Button>
                </TooltipTrigger>
                <TooltipContent>Insert Image</TooltipContent>
              </Tooltip>
            </div>

            {/* Enhanced Stream Info */}
            <div className="flex items-center gap-3">
              <div className="flex items-center gap-2">
                <Badge variant="outline" className="text-xs">
                  {(() => {
                    const icons = {
                      note: FileText,
                      idea: Lightbulb,
                      task: CheckSquare,
                      meeting: Users,
                      project: FolderOpen,
                      reference: BookOpen,
                      template: Copy
                    }
                    const Icon = icons[note.streamType as keyof typeof icons] || FileText
                    return <Icon className="w-3 h-3 mr-1" />
                  })()}
                  {note.streamType}
                </Badge>
                <Badge
                  variant={note.streamState === 'active' ? 'default' : 'secondary'}
                  className="text-xs"
                >
                  {note.streamState}
                </Badge>
              </div>

              <Separator orientation="vertical" className="h-6" />

              <div className="flex items-center gap-1">
                <Tooltip>
                  <TooltipTrigger asChild>
                    <Button variant="ghost" size="sm">
                      <Save className="w-4 h-4" />
                    </Button>
                  </TooltipTrigger>
                  <TooltipContent>Save (⌘S)</TooltipContent>
                </Tooltip>

                <Tooltip>
                  <TooltipTrigger asChild>
                    <Button variant="ghost" size="sm">
                      <Eye className="w-4 h-4" />
                    </Button>
                  </TooltipTrigger>
                  <TooltipContent>Preview</TooltipContent>
                </Tooltip>

                <Tooltip>
                  <TooltipTrigger asChild>
                    <Button variant="ghost" size="sm">
                      <Settings className="w-4 h-4" />
                    </Button>
                  </TooltipTrigger>
                  <TooltipContent>Settings</TooltipContent>
                </Tooltip>
              </div>
            </div>
          </div>
        </div>

        {/* Enhanced Note Content */}
        <div className="flex-1 flex flex-col overflow-hidden relative">
          <ScrollArea className="flex-1">
            <div className="relative min-h-full">
              <Textarea
                ref={textareaRef}
                value={content}
                onChange={handleTextareaChange}
                placeholder={`Stream Title

Start writing your content here...

💡 Tips:
• Use @[type:name] to reference other streams
• Examples: @[関連:Project A] @[依存:Task B]
• Use **bold** and *italic* formatting
• Press ⌘+K for quick search`}
                className="min-h-[400px] border-none shadow-none p-6 resize-none focus-visible:ring-0 text-base leading-relaxed bg-transparent prose prose-gray dark:prose-invert max-w-none focus:outline-none"
                style={{
                  fontFamily: 'inherit',
                  lineHeight: '1.7',
                  caretColor: 'hsl(var(--primary))'
                }}
              />

              {/* Enhanced Autocomplete Popup */}
              {autocomplete.show && (
                <div
                  className="absolute z-50 bg-popover border border-border rounded-lg shadow-lg max-w-sm animate-in fade-in-0 zoom-in-95"
                  style={{
                    top: autocomplete.position.top,
                    left: autocomplete.position.left
                  }}
                >
                  <Command className="w-full">
                    <CommandList>
                      <CommandEmpty className="py-6 text-center text-sm">
                        No matches found
                      </CommandEmpty>
                      <CommandGroup>
                        {getAutocompleteSuggestions().slice(0, 8).map((suggestion, index) => (
                          <CommandItem
                            key={index}
                            value={suggestion.value}
                            onSelect={() => handleAutocompleteSelect(suggestion.value)}
                            className="cursor-pointer flex items-center gap-3 px-3 py-2 hover:bg-accent focus:bg-accent"
                          >
                            {autocomplete.referenceType === null ? (
                              <div className="flex items-center gap-2">
                                <Badge variant="outline" className="text-xs font-medium">
                                  {suggestion.label}
                                </Badge>
                                <span className="text-xs text-muted-foreground">
                                  {REFERENCE_TYPE_LABELS[suggestion.label as ReferenceType]?.label}
                                </span>
                              </div>
                            ) : (
                              <div className="flex items-center gap-3 flex-1 min-w-0">
                                <div className="flex items-center gap-2 flex-1 min-w-0">
                                  {'stream' in suggestion && (
                                    (() => {
                                      const icons = {
                                        note: FileText,
                                        idea: Lightbulb,
                                        task: CheckSquare,
                                        meeting: Users,
                                        project: FolderOpen,
                                        reference: BookOpen,
                                        template: Copy
                                      }
                                      const Icon = icons[suggestion.stream.streamType as keyof typeof icons] || FileText
                                      return <Icon className="w-4 h-4 text-muted-foreground flex-shrink-0" />
                                    })()
                                  )}
                                  <span className="font-medium truncate">{suggestion.label}</span>
                                </div>
                                {'stream' in suggestion && (
                                  <Badge variant="outline" className="text-xs flex-shrink-0">
                                    {suggestion.stream.streamType}
                                  </Badge>
                                )}
                              </div>
                            )}
                          </CommandItem>
                        ))}
                      </CommandGroup>
                </CommandList>
              </Command>
            </div>
          )}
        </div>

        {/* Embedded References Preview */}
        {parseStreamReferences(content).length > 0 && (
          <div className="mb-6 p-3 bg-blue-50 rounded-lg">
            <h4 className="text-sm font-medium text-blue-900 mb-2 flex items-center gap-2">
              <AtSign className="w-4 h-4" />
              埋め込まれたストリーム参照 ({parseStreamReferences(content).length})
            </h4>
            <div className="flex flex-wrap gap-2">
              {parseStreamReferences(content).map((ref, index) => {
                const refInfo = REFERENCE_TYPE_LABELS[ref.referenceType]
                const targetStream = allNotes.find(n => 
                  extractTitleFromContent(n.content).toLowerCase() === ref.streamTitle.toLowerCase()
                )
                return (
                  <div key={index} className="flex items-center gap-1 p-2 bg-white rounded border">
                    <Badge className={`text-xs ${refInfo.color}`}>
                      {refInfo.label}
                    </Badge>
                    <ArrowRight className="w-3 h-3 text-gray-400" />
                    <span className="text-sm font-medium">
                      {ref.streamTitle}
                    </span>
                    {!targetStream && (
                      <Badge variant="destructive" className="text-xs">
                        未発見
                      </Badge>
                    )}
                  </div>
                )
              })}
            </div>
          </div>
        )}

        {/* Stream References Section */}
        <div className="space-y-6">
          <Separator />
          
          {/* Current References */}
          {note.references.filter(ref => !ref.context?.startsWith('embedded:')).length > 0 && (
            <Card>
              <CardHeader className="pb-3">
                <CardTitle className="text-sm font-medium flex items-center gap-2">
                  <GitBranch className="w-4 h-4" />
                  Manual Stream References ({note.references.filter(ref => !ref.context?.startsWith('embedded:')).length})
                </CardTitle>
              </CardHeader>
              <CardContent className="space-y-2">
                {note.references.filter(ref => !ref.context?.startsWith('embedded:')).map((ref) => {
                  const targetStream = allNotes.find(n => n.streamId === ref.targetStreamId)
                  const refInfo = REFERENCE_TYPE_LABELS[ref.referenceType]

                  return (
                    <div key={ref.id} className="flex items-center justify-between p-3 bg-muted/50 rounded-lg border border-border/50 hover:bg-muted/70 transition-colors">
                      <div className="flex items-center gap-3 flex-1 min-w-0">
                        <Badge className={`text-xs font-medium ${refInfo.color} border-0`}>
                          {refInfo.label}
                        </Badge>
                        <ArrowRight className="w-4 h-4 text-muted-foreground flex-shrink-0" />
                        <div className="flex-1 min-w-0">
                          <span className="text-sm font-medium block truncate">
                            {targetStream ? extractTitleFromContent(targetStream.content) : 'Unknown Stream'}
                          </span>
                          {ref.context && !ref.context.startsWith('embedded:') && (
                            <span className="text-xs text-muted-foreground block truncate mt-0.5">
                              {ref.context}
                            </span>
                          )}
                        </div>
                      </div>
                      <div className="flex items-center gap-1 ml-2">
                        <Tooltip>
                          <TooltipTrigger asChild>
                            <Button
                              variant="ghost"
                              size="sm"
                              className="h-8 w-8 p-0 hover:bg-accent"
                              title="Open referenced stream"
                            >
                              <ExternalLink className="w-4 h-4" />
                            </Button>
                          </TooltipTrigger>
                          <TooltipContent>Open stream</TooltipContent>
                        </Tooltip>
                        <Tooltip>
                          <TooltipTrigger asChild>
                            <Button
                              variant="ghost"
                              size="sm"
                              className="h-8 w-8 p-0 hover:bg-destructive hover:text-destructive-foreground"
                              onClick={() => removeStreamReference(ref.id)}
                              title="Remove reference"
                            >
                              <X className="w-4 h-4" />
                            </Button>
                          </TooltipTrigger>
                          <TooltipContent>Remove reference</TooltipContent>
                        </Tooltip>
                      </div>
                    </div>
                  )
                })}
              </CardContent>
            </Card>
          )}

          {/* Enhanced Add New Reference */}
          <Card className="border-dashed border-2 hover:border-primary/50 transition-colors">
            <CardHeader className="pb-3">
              <CardTitle className="text-sm font-medium flex items-center gap-2">
                <Plus className="w-4 h-4" />
                Add Stream Reference
              </CardTitle>
              <p className="text-xs text-muted-foreground">
                💡 Pro tip: Type @[type:name] in your text to embed references inline
              </p>
            </CardHeader>
            <CardContent className="space-y-4">
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                <div className="space-y-2">
                  <Label htmlFor="reference-type" className="text-xs font-medium">
                    Reference Type
                  </Label>
                  <Select value={newReferenceType} onValueChange={(value: ReferenceType) => setNewReferenceType(value)}>
                    <SelectTrigger id="reference-type" className="h-9">
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      {Object.entries(REFERENCE_TYPE_LABELS).map(([key, value]) => (
                        <SelectItem key={key} value={key}>
                          <div className="flex items-center gap-2">
                            <div className={`w-2 h-2 rounded-full ${value.color.split(' ')[0].replace('bg-', 'bg-')}`} />
                            <span className="font-medium">{value.label}</span>
                            <span className="text-xs text-muted-foreground ml-1">({value.shortCode})</span>
                          </div>
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                </div>

                <div className="space-y-2">
                  <Label htmlFor="reference-target" className="text-xs font-medium">
                    Target Stream
                  </Label>
                  <Select value={newReferenceTarget} onValueChange={setNewReferenceTarget}>
                    <SelectTrigger id="reference-target" className="h-9">
                      <SelectValue placeholder="Choose a stream..." />
                    </SelectTrigger>
                    <SelectContent>
                      {availableStreams.map((stream) => (
                        <SelectItem key={stream.streamId} value={stream.streamId}>
                          <div className="flex items-center gap-2 w-full">
                            {(() => {
                              const icons = {
                                note: FileText,
                                idea: Lightbulb,
                                task: CheckSquare,
                                meeting: Users,
                                project: FolderOpen,
                                reference: BookOpen,
                                template: Copy
                              }
                              const Icon = icons[stream.streamType as keyof typeof icons] || FileText
                              return <Icon className="w-4 h-4 text-muted-foreground" />
                            })()}
                            <div className="flex-1 min-w-0">
                              <div className="font-medium truncate">
                                {extractTitleFromContent(stream.content)}
                              </div>
                              <div className="text-xs text-muted-foreground">
                                {stream.streamType}
                              </div>
                            </div>
                          </div>
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                </div>
              </div>

              <div className="space-y-2">
                <Label htmlFor="reference-context" className="text-xs font-medium">
                  Context (Optional)
                </Label>
                <Input
                  id="reference-context"
                  value={newReferenceContext}
                  onChange={(e) => setNewReferenceContext(e.target.value)}
                  placeholder="Why are you referencing this stream?"
                  className="h-9"
                />
              </div>
              
              <div className="flex gap-2">
                <Button
                  onClick={addStreamReference}
                  size="sm"
                  disabled={!newReferenceTarget}
                  className="flex-1"
                >
                  <Plus className="w-4 h-4 mr-2" />
                  Add Reference
                </Button>
                <Button
                  variant="outline"
                  size="sm"
                  onClick={() => {
                    setNewReferenceTarget("")
                    setNewReferenceContext("")
                  }}
                >
                  Clear
                </Button>
              </div>
            </CardContent>
          </Card>

          {/* Enhanced Tags Section */}
          <Card>
            <CardHeader className="pb-3">
              <CardTitle className="text-sm font-medium flex items-center gap-2">
                <Tag className="w-4 h-4" />
                Tags
                <Badge variant="secondary" className="text-xs">
                  {note.tags.length}
                </Badge>
              </CardTitle>
            </CardHeader>
            <CardContent className="space-y-4">
              {/* Existing Tags */}
              {note.tags.length > 0 && (
                <div className="flex flex-wrap gap-2">
                  {note.tags.map((tag) => (
                    <Tooltip key={tag}>
                      <TooltipTrigger asChild>
                        <Badge variant="secondary" className="flex items-center gap-1 px-3 py-1 hover:bg-secondary/80 cursor-pointer group">
                          <Hash className="w-3 h-3" />
                          {tag}
                          <Button
                            variant="ghost"
                            size="sm"
                            className="h-4 w-4 p-0 ml-1 hover:bg-destructive hover:text-destructive-foreground opacity-0 group-hover:opacity-100 transition-opacity"
                            onClick={() => removeTag(tag)}
                          >
                            <X className="w-3 h-3" />
                          </Button>
                        </Badge>
                      </TooltipTrigger>
                      <TooltipContent>
                        Click X to remove tag
                      </TooltipContent>
                    </Tooltip>
                  ))}
                </div>
              )}

              {/* Add New Tag */}
              <div className="space-y-2">
                <Label htmlFor="new-tag" className="text-xs font-medium">
                  Add New Tag
                </Label>
                <div className="flex gap-2">
                  <Input
                    id="new-tag"
                    value={newTag}
                    onChange={(e) => setNewTag(e.target.value)}
                    placeholder="Enter tag name..."
                    className="flex-1 h-9"
                    onKeyPress={(e) => e.key === "Enter" && addTag()}
                  />
                  <Button
                    onClick={addTag}
                    size="sm"
                    disabled={!newTag.trim()}
                    className="px-4"
                  >
                    <Plus className="w-4 h-4 mr-2" />
                    Add Tag
                  </Button>
                </div>
                <p className="text-xs text-muted-foreground">
                  Tags help organize and find your streams. Press Enter or click Add to create.
                </p>
              </div>
            </CardContent>
          </Card>
        </div>
      </div>
    </div>
  )
}
