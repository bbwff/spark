/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.internal.io

import java.io.IOException

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs._
import org.apache.hadoop.mapreduce._
import org.apache.hadoop.mapreduce.lib.output.{FileOutputCommitter, FileOutputFormat}

import org.apache.spark.internal.Logging
import org.apache.spark.util.Utils


/**
 * An interface to define how a single Spark job commits its outputs. Three notes:
 *
 * 1. Implementations must be serializable, as the committer instance instantiated on the driver
 *    will be used for tasks on executors.
 * 2. Implementations should have a constructor with 2 or 3 arguments:
 *      (jobId: String, path: String) or
 *      (jobId: String, path: String, dynamicPartitionOverwrite: Boolean)
 * 3. A committer should not be reused across multiple Spark jobs.
 *
 * The proper call sequence is:
 *
 * 1. Driver calls setupJob.
 * 2. As part of each task's execution, executor calls setupTask and then commitTask
 *    (or abortTask if task failed).
 * 3. When all necessary tasks completed successfully, the driver calls commitJob. If the job
 *    failed to execute (e.g. too many failed tasks), the job should call abortJob.
 */
abstract class FileCommitProtocol extends Logging {
  import FileCommitProtocol._

  /**
   * Setups up a job. Must be called on the driver before any other methods can be invoked.
   */
  def setupJob(jobContext: JobContext): Unit

  /**
   * Commits a job after the writes succeed. Must be called on the driver.
   */
  def commitJob(jobContext: JobContext, taskCommits: Seq[TaskCommitMessage]): Unit

  /**
   * Aborts a job after the writes fail. Must be called on the driver.
   *
   * Calling this function is a best-effort attempt, because it is possible that the driver
   * just crashes (or killed) before it can call abort.
   */
  def abortJob(jobContext: JobContext): Unit

  /**
   * Sets up a task within a job.
   * Must be called before any other task related methods can be invoked.
   */
  def setupTask(taskContext: TaskAttemptContext): Unit

  /**
   * Notifies the commit protocol to add a new file, and gets back the full path that should be
   * used. Must be called on the executors when running tasks.
   *
   * Note that the returned temp file may have an arbitrary path. The commit protocol only
   * promises that the file will be at the location specified by the arguments after job commit.
   *
   * A full file path consists of the following parts:
   *  1. the base path
   *  2. some sub-directory within the base path, used to specify partitioning
   *  3. file prefix, usually some unique job id with the task id
   *  4. bucket id
   *  5. source specific file extension, e.g. ".snappy.parquet"
   *
   * The "dir" parameter specifies 2, and "ext" parameter specifies both 4 and 5, and the rest
   * are left to the commit protocol implementation to decide.
   *
   * Important: it is the caller's responsibility to add uniquely identifying content to "ext"
   * if a task is going to write out multiple files to the same dir. The file commit protocol only
   * guarantees that files written by different tasks will not conflict.
   */
  def newTaskTempFile(taskContext: TaskAttemptContext, dir: Option[String], ext: String): String

  /**
   * Similar to newTaskTempFile(), but allows files to committed to an absolute output location.
   * Depending on the implementation, there may be weaker guarantees around adding files this way.
   *
   * Important: it is the caller's responsibility to add uniquely identifying content to "ext"
   * if a task is going to write out multiple files to the same dir. The file commit protocol only
   * guarantees that files written by different tasks will not conflict.
   */
  def newTaskTempFileAbsPath(
      taskContext: TaskAttemptContext, absoluteDir: String, ext: String): String

  /**
   * Commits a task after the writes succeed. Must be called on the executors when running tasks.
   */
  def commitTask(taskContext: TaskAttemptContext): TaskCommitMessage

  /**
   * Aborts a task after the writes have failed. Must be called on the executors when running tasks.
   *
   * Calling this function is a best-effort attempt, because it is possible that the executor
   * just crashes (or killed) before it can call abort.
   */
  def abortTask(taskContext: TaskAttemptContext): Unit

  /**
   * Specifies that a file should be deleted with the commit of this job. The default
   * implementation deletes the file immediately.
   */
  def deleteWithJob(fs: FileSystem, path: Path, recursive: Boolean): Boolean = {
    fs.delete(path, recursive)
  }

  /**
   * Called on the driver after a task commits. This can be used to access task commit messages
   * before the job has finished. These same task commit messages will be passed to commitJob()
   * if the entire job succeeds.
   */
  def onTaskCommit(taskCommit: TaskCommitMessage): Unit = {
    logDebug(s"onTaskCommit($taskCommit)")
  }
}


object FileCommitProtocol extends Logging {
  class TaskCommitMessage(val obj: Any) extends Serializable

  object EmptyTaskCommitMessage extends TaskCommitMessage(null)

  /**
   * Instantiates a FileCommitProtocol using the given className.
   */
  def instantiate(
      className: String,
      jobId: String,
      outputPath: String,
      dynamicPartitionOverwrite: Boolean = false,
      isInsertIntoHadoopFsRelation: Boolean = false,
      isOverwrite: Boolean = false,
      staticPartitionKVs: Seq[(String, String)] = Seq.empty[(String, String)]):
  FileCommitProtocol = {

    logDebug(s"Creating committer $className; job $jobId; output=$outputPath;" +
      s" dynamic=$dynamicPartitionOverwrite;" +
      s" isInsertIntoHadoopFsRelation=$isInsertIntoHadoopFsRelation; isOverwrite=$isOverwrite;" +
      s" staticPartitionKVS=$staticPartitionKVs")
    val clazz = Utils.classForName[FileCommitProtocol](className)
    // First try the constructor with arguments (jobId: String, outputPath: String,
    // dynamicPartitionOverwrite: Boolean, isInsertIntoHadoopFsRelation: Boolean,
    // isOverwrite: Boolean, staticPartitionKVs: Seq[(String, String)]).
    // If that doesn't exist, try the one with (jobId: string, outputPath: String).
    try {
      val ctor = clazz.getDeclaredConstructor(classOf[String], classOf[String], classOf[Boolean],
        classOf[Boolean], classOf[Boolean], classOf[Seq[(String, String)]])
      logDebug("Using (String, String, Boolean, Boolean, Boolean, Seq[(String, String)])" +
        " constructor")
      ctor.newInstance(jobId, outputPath, dynamicPartitionOverwrite.asInstanceOf[java.lang.Boolean],
        isInsertIntoHadoopFsRelation.asInstanceOf[java.lang.Boolean],
        isOverwrite.asInstanceOf[java.lang.Boolean], staticPartitionKVs)
    } catch {
      case _: NoSuchMethodException =>
        logDebug("Falling back to (String, String) constructor")
        require(!dynamicPartitionOverwrite,
          "Dynamic Partition Overwrite is enabled but" +
            s" the committer ${className} does not have the appropriate constructor")
        val ctor = clazz.getDeclaredConstructor(classOf[String], classOf[String])
        ctor.newInstance(jobId, outputPath)
    }
  }

  /**
   * Commit all committed task output to a outputPath directly.
   * These code referred the implementation of [[FileOutputCommitter]],
   * please keep consistent with it.
   */
  @throws[IOException]
  def commitJob(
      committer: FileOutputCommitter,
      context: JobContext,
      outputPath: Path): Unit = {
    val algorithmVersion = getFieldValue(committer, "algorithmVersion").asInstanceOf[Int]
    val maxAttemptsOnFailure = if (algorithmVersion == 2) {
      context.getConfiguration.getInt("mapreduce.fileoutputcommitter.failures.attempts", 1)
    } else {
      1
    }
    var attempt = 0
    var jobCommitNotFinished = true
    while (jobCommitNotFinished) {
      try {
        commitJobInternal(committer, context, algorithmVersion, outputPath)
        jobCommitNotFinished = false
      } catch {
        case e: Exception =>
          if (attempt + 1 > maxAttemptsOnFailure) {
            throw e
          } else {
            attempt += 1
            logWarning(s"Exception get thrown in job commit, retry ($attempt) time.", e)
          }
      }
    }
    if (committer.getClass.getName.endsWith("ParquetOutputCommitter")) {
      // Please keep consistent with the implementation of ParquetOutputCommitter.commitJob().
      var parquetCommitter: Class[_] = null
      var contextUtil: Class[_] = null

      try {
        parquetCommitter = Utils.classForName("org.apache.parquet.hadoop.ParquetOutputCommitter")
        contextUtil = Utils.classForName("org.apache.parquet.hadoop.util.ContextUtil")
      } catch {
        case _: ClassNotFoundException =>
          parquetCommitter = Utils.classForName("parquet.hadoop.ParquetOutputCommitter")
          contextUtil = Utils.classForName("parquet.hadoop.util.ContextUtil")
      }

      val getConfiguration = contextUtil.getMethod("getConfiguration", classOf[JobContext])
      val writeMetadata = parquetCommitter.getMethod("writeMetaDataFile", classOf[Configuration],
        classOf[Path])
      val configuration = getConfiguration.invoke(null, context)
      writeMetadata.invoke(null, configuration, outputPath)
    }
  }

  /**
   * The job has completed, so do following commit job, include:
   * Move all committed tasks to the final output dir (algorithm 1 only).
   * Delete the temporary directory, including all of the work directories.
   * Create a _SUCCESS file to make it as successful.
   *
   * Copied from [[FileOutputCommitter]], please keep consistent with it.
   */
  @throws[IOException]
  private def commitJobInternal(
      committer: FileOutputCommitter,
      context: JobContext,
      algorithmVersion: Int,
      outputPath: Path): Unit = {
    if (outputPath != null) {
      val fs = outputPath.getFileSystem(context.getConfiguration)

      if (algorithmVersion == 1) {
        for (stat <- getAllCommittedTaskPaths(committer, context)) {
          mergePaths(committer, fs, stat, outputPath)
        }
      } else {
        invokeMethod(committer, "cleanupJob", Seq(classOf[JobContext]), Seq(context))
        val stagingOutput = new Path(context.getConfiguration.get(FileOutputFormat.OUTDIR))
        mergePaths(committer, fs, fs.getFileStatus(stagingOutput), outputPath)
      }

      if (context.getConfiguration.getBoolean("mapreduce.fileoutputcommitter.marksuccessfuljobs",
        true)) {
        val markerPath = new Path(outputPath, "_SUCCESS")
        if (algorithmVersion == 2) {
          fs.create(markerPath, true).close()
        } else {
          fs.create(markerPath)
        }
      }
    } else {
      logWarning("Output Path is null in commitJob()")
    }
  }

  /**
   * Invoke a method from the Class of instance or from its superclasses.
   */
  private def invokeMethod(
      instance: Any,
      methodName: String,
      argTypes: Seq[Class[_]],
      params: Seq[AnyRef]): Any = {
    var clazz: Class[_ <: Any] = instance.getClass
    while (clazz != null) {
      try {
        val method = clazz.getDeclaredMethod(methodName, argTypes: _*)
        method.setAccessible(true)
        val r = method.invoke(instance, params: _*)
        method.setAccessible(false)
        return r
      } catch {
        case _: NoSuchMethodException =>
          logDebug(s"Can not get $methodName method from $clazz, try to get from its superclass:" +
            s" ${clazz.getSuperclass}")
          clazz = clazz.getSuperclass
      }
    }
    throw new NoSuchMethodException(s"Can not get $methodName method from ${instance.getClass}" +
      s" and its superclasses")
  }

  /**
   * Get a field value from the Class of instance or from its superclasses.
   */
  private def getFieldValue(
      instance: Any,
      name: String): Any = {
    var clazz: Class[_ <: Any] = instance.getClass
    while (clazz != null) {
      try {
        val field = clazz.getDeclaredField(name)
        field.setAccessible(true)
        val r = field.get(instance)
        field.setAccessible(false)
        return r
      } catch {
        case _: NoSuchFieldException =>
          logDebug(s"Can not get $name from $clazz, try to get from its superclass:" +
            s" ${clazz.getSuperclass}")
          clazz = clazz.getSuperclass
      }
    }
    throw new NoSuchFieldException(s"Can not get $name from ${instance.getClass}" +
      s" and its superclasses")
  }

  /**
   * Invoke the `mergePaths` method of a FileOutputCommitter instance.
   */
  @throws[IOException]
  private def mergePaths(
      committer: FileOutputCommitter,
      fs: FileSystem,
      from: FileStatus,
      to: Path): Unit = {
    invokeMethod(committer, "mergePaths", Seq(classOf[FileSystem], classOf[FileStatus],
      classOf[Path]),
      Seq(fs, from, to))
  }

  /**
   * Invoke the `getAllCommittedTaskPaths` method of a FileOutputCommitter instance.
   */
  @throws[IOException]
  private def getAllCommittedTaskPaths(
      committer: FileOutputCommitter,
      context: JobContext): Array[FileStatus] = {
    invokeMethod(committer, "getAllCommittedTaskPaths", Seq(classOf[JobContext]), Seq(context))
      .asInstanceOf[Array[FileStatus]]
  }
}
