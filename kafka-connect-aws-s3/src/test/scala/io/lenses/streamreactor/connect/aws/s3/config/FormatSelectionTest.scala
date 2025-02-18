/*
 * Copyright 2017-2023 Lenses.io Ltd
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
package io.lenses.streamreactor.connect.aws.s3.config

import io.lenses.streamreactor.connect.aws.s3.config.FormatOptions.WithHeaders
import io.lenses.streamreactor.connect.aws.s3.model.CompressionCodec
import io.lenses.streamreactor.connect.aws.s3.model.CompressionCodecName.UNCOMPRESSED
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class FormatSelectionTest extends AnyFlatSpec with Matchers {

  implicit val compressionCodec: CompressionCodec = UNCOMPRESSED.toCodec()

  "formatSelection.fromString" should "format for CSV with headers" in {
    FormatSelection.fromString("`CSV_WITHHEADERS`", () => Option.empty) should be(
      Right(CsvFormatSelection(Set(WithHeaders))),
    )
  }

  "formatSelection.fromString" should "format for CSV without headers" in {
    FormatSelection.fromString("`CSV`", () => Option.empty) should be(Right(CsvFormatSelection(Set.empty)))
  }
}
