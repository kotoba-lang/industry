"use client"

import { useState, useEffect, useRef, useCallback } from "react"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Textarea } from "@/components/ui/textarea"
import { Badge } from "@/components/ui/badge"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Separator } from "@/components/ui/separator"
import { Command, CommandEmpty, CommandGroup, CommandInput, CommandItem, CommandList } from "@/components/ui/command"
import { ImageIcon, Bold, Italic, List, Link, Hash, X, GitBranch, ArrowRight, ExternalLink, AtSign } from "lucide-react"
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
      <div className="flex-1 flex items-center justify-center bg-gray-50">
        <div className="text-center">
          <div className="w-16 h-16 bg-gray-200 rounded-full flex items-center justify-center mx-auto mb-4">
            <ImageIcon className="w-8 h-8 text-gray-400" />
          </div>
          <h2 className="text-xl font-medium text-gray-900 mb-2">No stream selected</h2>
          <p className="text-gray-600 mb-4">Select a stream from the sidebar or create a new one</p>
          <Button onClick={onNewNote} className="bg-blue-600 hover:bg-blue-700 text-white">
            Create New Stream
          </Button>
        </div>
      </div>
    )
  }

  return (
    <div className="flex-1 flex flex-col bg-white">
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

      {/* Editor Toolbar */}
      <div className="border-b border-gray-200 p-3 flex items-center justify-between">
        <div className="flex items-center gap-2">
          <Button variant="ghost" size="sm" onClick={() => insertFormatting("bold")} title="Bold">
            <Bold className="w-4 h-4" />
          </Button>
          <Button variant="ghost" size="sm" onClick={() => insertFormatting("italic")} title="Italic">
            <Italic className="w-4 h-4" />
          </Button>
          <Button variant="ghost" size="sm" onClick={() => insertFormatting("list")} title="List">
            <List className="w-4 h-4" />
          </Button>
          <Button variant="ghost" size="sm" onClick={() => insertFormatting("link")} title="Link">
            <Link className="w-4 h-4" />
          </Button>
          <div className="w-px h-6 bg-gray-300 mx-2" />
          <Button variant="ghost" size="sm" onClick={() => insertFormatting("stream-ref")} title="Insert Stream Reference">
            <AtSign className="w-4 h-4" />
          </Button>
          <Button variant="ghost" size="sm" title="Insert Image" onClick={() => console.log("Insert Image clicked")}>
            <ImageIcon className="w-4 h-4" />
          </Button>
        </div>
        
        {/* Stream Info */}
        <div className="flex items-center gap-2">
          <Badge variant="outline" className="text-xs">
            <GitBranch className="w-3 h-3 mr-1" />
            {note.streamType}
          </Badge>
          <Badge variant="secondary" className="text-xs">
            {note.streamState}
          </Badge>
        </div>
      </div>

      {/* Note Content */}
      <div className="flex-1 flex flex-col p-6 overflow-hidden relative">
        <div className="relative">
          <Textarea
            ref={textareaRef}
            value={content}
            onChange={handleTextareaChange}
            placeholder="タイトルを最初の1行目に入力してください&#10;&#10;改行して本文を書き始めてください...&#10;&#10;ストリーム参照を埋め込むには @[参照タイプ:ストリーム名] の形式で入力してください&#10;例: @[関連:プロジェクトA] @[依存:タスクB]"
            className="flex-1 border-none shadow-none p-0 resize-none focus-visible:ring-0 text-base leading-relaxed mb-6"
          />

          {/* Autocomplete Popup */}
          {autocomplete.show && (
            <div
              className="absolute z-50 bg-white border border-gray-200 rounded-lg shadow-lg max-w-sm"
              style={{
                top: autocomplete.position.top,
                left: autocomplete.position.left
              }}
            >
              <Command className="w-full">
                <CommandList>
                  <CommandEmpty>候補が見つかりません</CommandEmpty>
                  <CommandGroup>
                    {getAutocompleteSuggestions().map((suggestion, index) => (
                      <CommandItem
                        key={index}
                        value={suggestion.value}
                        onSelect={() => handleAutocompleteSelect(suggestion.value)}
                        className="cursor-pointer"
                      >
                        <div className="flex items-center gap-2">
                          {autocomplete.referenceType === null ? (
                            <Badge variant="outline" className="text-xs">
                              {suggestion.label}
                            </Badge>
                          ) : (
                            <>
                              <span className="font-medium">{suggestion.label}</span>
                              {'stream' in suggestion && (
                                <Badge variant="outline" className="text-xs">
                                  {suggestion.stream.streamType}
                                </Badge>
                              )}
                            </>
                          )}
                        </div>
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
                    <div key={ref.id} className="flex items-center justify-between p-2 bg-gray-50 rounded-lg">
                      <div className="flex items-center gap-2 flex-1">
                        <Badge className={`text-xs ${refInfo.color}`}>
                          {refInfo.label}
                        </Badge>
                        <ArrowRight className="w-3 h-3 text-gray-400" />
                        <span className="text-sm font-medium">
                          {targetStream ? extractTitleFromContent(targetStream.content) : 'Unknown Stream'}
                        </span>
                        {ref.context && !ref.context.startsWith('embedded:') && (
                          <span className="text-xs text-gray-500">({ref.context})</span>
                        )}
                      </div>
                      <div className="flex items-center gap-1">
                        <Button
                          variant="ghost"
                          size="sm"
                          className="h-6 w-6 p-0"
                          title="Open referenced stream"
                        >
                          <ExternalLink className="w-3 h-3" />
                        </Button>
                        <button 
                          onClick={() => removeStreamReference(ref.id)} 
                          className="text-gray-400 hover:text-red-500"
                          title="Remove reference"
                        >
                          <X className="w-3 h-3" />
                        </button>
                      </div>
                    </div>
                  )
                })}
              </CardContent>
            </Card>
          )}

          {/* Add New Reference */}
          <Card>
            <CardHeader className="pb-3">
              <CardTitle className="text-sm font-medium">Add Stream Reference</CardTitle>
              <p className="text-xs text-gray-500">
                ヒント: テキスト内で @[参照タイプ:ストリーム名] と入力することで直接埋め込むこともできます
              </p>
            </CardHeader>
            <CardContent className="space-y-3">
              <div className="grid grid-cols-2 gap-3">
                <Select value={newReferenceType} onValueChange={(value: ReferenceType) => setNewReferenceType(value)}>
                  <SelectTrigger className="h-8 text-sm">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {Object.entries(REFERENCE_TYPE_LABELS).map(([key, value]) => (
                      <SelectItem key={key} value={key}>
                        <span className={`inline-block w-2 h-2 rounded-full mr-2 ${value.color.split(' ')[0]}`}></span>
                        {value.label}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
                
                <Select value={newReferenceTarget} onValueChange={setNewReferenceTarget}>
                  <SelectTrigger className="h-8 text-sm">
                    <SelectValue placeholder="Select stream..." />
                  </SelectTrigger>
                  <SelectContent>
                    {availableStreams.map((stream) => (
                      <SelectItem key={stream.streamId} value={stream.streamId}>
                        <div className="flex items-center gap-2">
                          <Badge variant="outline" className="text-xs">
                            {stream.streamType}
                          </Badge>
                          {extractTitleFromContent(stream.content)}
                        </div>
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>
              
              <Input
                value={newReferenceContext}
                onChange={(e) => setNewReferenceContext(e.target.value)}
                placeholder="Context (optional)..."
                className="h-8 text-sm"
              />
              
              <Button 
                onClick={addStreamReference} 
                size="sm" 
                disabled={!newReferenceTarget}
                className="w-full"
              >
                Add Reference
              </Button>
            </CardContent>
          </Card>

          {/* Tags Section */}
          <Card>
            <CardHeader className="pb-3">
              <CardTitle className="text-sm font-medium flex items-center gap-2">
                <Hash className="w-4 h-4" />
                Tags
              </CardTitle>
            </CardHeader>
            <CardContent className="space-y-3">
              <div className="flex flex-wrap gap-2">
                {note.tags.map((tag) => (
                  <Badge key={tag} variant="secondary" className="flex items-center gap-1">
                    {tag}
                    <button 
                      onClick={() => removeTag(tag)} 
                      className="ml-1 hover:text-red-500"
                      title={`Remove ${tag} tag`}
                    >
                      <X className="w-3 h-3" />
                    </button>
                  </Badge>
                ))}
              </div>

              <div className="flex gap-2">
                <Input
                  value={newTag}
                  onChange={(e) => setNewTag(e.target.value)}
                  placeholder="Add tag..."
                  className="flex-1 h-8 text-sm"
                  onKeyPress={(e) => e.key === "Enter" && addTag()}
                />
                <Button onClick={addTag} size="sm" disabled={!newTag}>
                  Add
                </Button>
              </div>
            </CardContent>
          </Card>
        </div>
      </div>
    </div>
  )
}
