/*-
 * #%L
 * rapidoid-rest
 * %%
 * Copyright (C) 2014 - 2020 Nikolche Mihajlovski and contributors
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

package org.rapidoid.docs.httpreq1;

import org.rapidoid.annotation.IntegrationTest;
import org.rapidoid.docs.DocTest;
import org.rapidoid.test.Doc;

@IntegrationTest(main = Main.class)
@Doc(title = "All the request data is in the (Req req) parameter")
public class HttpReqInfoTest extends DocTest {

	@Override
	protected void exercise() {
		GET("/showVerb");
		GET("/showPath");
		GET("/showUri");
		GET("/showData?x=1&y=abc");
	}

}