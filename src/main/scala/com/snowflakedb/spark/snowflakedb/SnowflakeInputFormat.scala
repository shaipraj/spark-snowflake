/*
 * Copyright 2014 Databricks
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.snowflakedb.spark.snowflakedb

import java.io.{BufferedInputStream, IOException}
import java.lang.{Long => JavaLong}
import java.nio.charset.Charset

import scala.collection.mutable.ArrayBuffer

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileSystem, Path}
import org.apache.hadoop.io.compress.CompressionCodecFactory
import org.apache.hadoop.mapreduce.lib.input.{FileInputFormat, FileSplit}
import org.apache.hadoop.mapreduce.{InputSplit, RecordReader, TaskAttemptContext}

/**
 * Input format for text records saved with in-record delimiter and newline characters escaped.
 *
 * Note, Snowflake exports fields where
 * - strings/dates are "-quoted, with a " inside represented as ""
 * - numbers are not quoted
 * - nulls are empty, unquoted strings
 */
class SnowflakeInputFormat extends FileInputFormat[JavaLong, Array[String]] {

  override def createRecordReader(
      split: InputSplit,
      context: TaskAttemptContext): RecordReader[JavaLong, Array[String]] = {
    new SnowflakeRecordReader
  }
}

object SnowflakeInputFormat {

  /** configuration key for delimiter */
  val KEY_DELIMITER = "snowflake.delimiter"
  /** default delimiter */
  val DEFAULT_DELIMITER = '|'

  /** Gets the delimiter char from conf or the default. */
  private[snowflakedb] def getDelimiterOrDefault(conf: Configuration): Char = {
    val c = conf.get(KEY_DELIMITER, DEFAULT_DELIMITER.toString)
    if (c.length != 1) {
      throw new IllegalArgumentException(s"Expect delimiter be a single character but got '$c'.")
    } else {
      c.charAt(0)
    }
  }
}

private[snowflakedb] class SnowflakeRecordReader extends RecordReader[JavaLong, Array[String]] {

  private var reader: BufferedInputStream = _

  private var key: JavaLong = _
  private var value: Array[String] = _

  private var start: Long = _
  private var end: Long = _
  private var cur: Long = _

  private var eof: Boolean = false

  private var delimiter: Byte = _
  @inline private[this] final val escapeChar: Byte = '\\'
  @inline private[this] final val quoteChar: Byte = '"'
  @inline private[this] final val lineFeed: Byte = '\n'
  @inline private[this] final val carriageReturn: Byte = '\r'

  @inline private[this] final val defaultBufferSize = 1024 * 1024

  private[this] val chars = ArrayBuffer.empty[Byte]

  override def initialize(inputSplit: InputSplit, context: TaskAttemptContext): Unit = {
    val split = inputSplit.asInstanceOf[FileSplit]
    val file = split.getPath
    val conf: Configuration = {
      // Use reflection to get the Configuration. This is necessary because TaskAttemptContext is
      // a class in Hadoop 1.x and an interface in Hadoop 2.x.
      val method = context.getClass.getMethod("getConfiguration")
      method.invoke(context).asInstanceOf[Configuration]
    }
    delimiter = SnowflakeInputFormat.getDelimiterOrDefault(conf).asInstanceOf[Byte]
    require(delimiter != escapeChar,
      s"The delimiter and the escape char cannot be the same but found $delimiter.")
    require(delimiter != lineFeed, "The delimiter cannot be the lineFeed character.")
    require(delimiter != carriageReturn, "The delimiter cannot be the carriage return.")
    val compressionCodecs = new CompressionCodecFactory(conf)
    val codec = compressionCodecs.getCodec(file)
    if (codec != null) {
      throw new IOException(s"Do not support compressed files but found $file with codec $codec.")
    }
    val fs = file.getFileSystem(conf)
    val size = fs.getFileStatus(file).getLen
    start = findNext(fs, file, size, split.getStart)
    end = findNext(fs, file, size, split.getStart + split.getLength)
    cur = start
    val in = fs.open(file)
    if (cur > 0L) {
      in.seek(cur - 1L)
      in.read()
    }
    reader = new BufferedInputStream(in, defaultBufferSize)
  }

  override def getProgress: Float = {
    if (start >= end) {
      1.0f
    } else {
      math.min((cur - start).toFloat / (end - start), 1.0f)
    }
  }

  override def nextKeyValue(): Boolean = {
    if (cur < end && !eof) {
      key = cur
      value = nextValue()
      true
    } else {
      key = null
      value = null
      false
    }
  }

  override def getCurrentValue: Array[String] = value

  override def getCurrentKey: JavaLong = key

  override def close(): Unit = {
    if (reader != null) {
      reader.close()
    }
  }

  /**
   * Finds the start of the next record.
   * Because we don't know whether the first char is escaped or not, we need to first find a
   * position that is not escaped.
   *
   * Snowflake-todo: Make it work for Snowflake format, or disable it
   *
   * @param fs file system
   * @param file file path
   * @param size file size
   * @param offset start offset
   * @return the start position of the next record
   */
  private def findNext(fs: FileSystem, file: Path, size: Long, offset: Long): Long = {
    if (offset == 0L) {
      return 0L
    } else if (offset >= size) {
      return size
    }
    val in = fs.open(file)
    var pos = offset
    in.seek(pos)
    val bis = new BufferedInputStream(in, defaultBufferSize)
    // Find the first unescaped char.
    var escaped = true
    var thisEof = false
    while (escaped && !thisEof) {
      val v = bis.read()
      if (v < 0) {
        thisEof = true
      } else {
        pos += 1
        if (v != escapeChar) {
          escaped = false
        }
      }
    }
    // Find the next unescaped line feed.
    var endOfRecord = false
    while ((escaped || !endOfRecord) && !thisEof) {
      val v = bis.read()
      if (v < 0) {
        thisEof = true
      } else {
        pos += 1
        if (v == escapeChar) {
          escaped = true
        } else {
          if (!escaped) {
            endOfRecord = v == lineFeed
          } else {
            escaped = false
          }
        }
      }
    }
    in.close()
    pos
  }

  private def nextChar() : Byte = {
    val v = reader.read()
    if (v < 0) {
      eof = true
      0
    } else {
      val c = v.asInstanceOf[Byte]
      cur += 1L
      c
    }
  }

  /** Read the next record.
    * Note - special return format:
    * - input non-quoted fields are returned as they are
    * - input quoted fields are returned *with* quotes
    * --- if there was a double quote inside, it's converted to a single quote
    * This is to distinguish NULLs (empty) from empty string (two quotes)*/
  private def nextValue(): Array[String] = {
    val fields = ArrayBuffer.empty[String]
    var escaped = false
    var endOfRecord = false
    while (!endOfRecord && !eof) {
      chars.clear()
      var endOfField = false
      // Read the first char
      var c = nextChar()
      if (!eof) {
        if (c == quoteChar) {
          // Quoted string - the only escape is doubling quoteChar
          // Note: we store beginning-end quotes
          var escaped = false
          chars.append(c)
          while (!endOfField && !endOfRecord && !eof) {
            c = nextChar()
            if (!eof) {
              if (!escaped) {
                if (c == quoteChar)
                  escaped = true
                chars.append(c)
              } else {
                if (c == delimiter) {
                  endOfField = true
                } else if (c == lineFeed) {
                  endOfRecord = true
                } else if (c == quoteChar) {
                  // Don't produce this quote
                  escaped = false
                }
              }
            }
          }
        } else {
          // Normal string
          // Note, 'c' is initialized above already
          while (!endOfField && !endOfRecord && !eof) {
            if (c == delimiter) {
              endOfField = true
            } else if (c == lineFeed) {
              endOfRecord = true
            } else {
              // Normal character, just append it
              chars.append(c)
              c = nextChar()
            }
          }
        }
      }
      // TODO: charset?
      fields.append(new String(chars.toArray, Charset.forName("UTF-8")))
    }
    if (escaped) {
      throw new IllegalStateException(s"Found hanging escape char.")
    }
    fields.toArray
  }
}

