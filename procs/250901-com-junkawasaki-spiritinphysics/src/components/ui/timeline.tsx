import * as React from "react"
import { cn } from "@/00_schema/utils"

function Timeline({
  className,
  ...props
}: React.HTMLAttributes<HTMLDivElement>) {
  return (
    <div
      className={cn("relative", className)}
      {...props}
    />
  )
}

function TimelineItem({
  className,
  ...props
}: React.HTMLAttributes<HTMLDivElement>) {
  return (
    <div
      className={cn("relative flex items-start", className)}
      {...props}
    />
  )
}

function TimelineSeparator({
  className,
  ...props
}: React.HTMLAttributes<HTMLDivElement>) {
  return (
    <div
      className={cn("flex flex-col items-center", className)}
      {...props}
    />
  )
}

function TimelineConnector({
  className,
  ...props
}: React.HTMLAttributes<HTMLDivElement>) {
  return (
    <div
      className={cn("w-px h-6 bg-border", className)}
      {...props}
    />
  )
}

function TimelineContent({
  className,
  ...props
}: React.HTMLAttributes<HTMLDivElement>) {
  return (
    <div
      className={cn("flex-1", className)}
      {...props}
    />
  )
}

function TimelineDot({
  className,
  ...props
}: React.HTMLAttributes<HTMLDivElement>) {
  return (
    <div
      className={cn(
        "flex h-8 w-8 items-center justify-center rounded-full border bg-background",
        className
      )}
      {...props}
    />
  )
}

export {
  Timeline,
  TimelineItem,
  TimelineSeparator,
  TimelineConnector,
  TimelineContent,
  TimelineDot,
}
