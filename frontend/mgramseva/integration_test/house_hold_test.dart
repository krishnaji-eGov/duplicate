
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';
import 'package:mgramseva/main.dart' as app;
import 'package:mgramseva/utils/Locilization/application_localizations.dart';
import 'package:mgramseva/utils/TestingKeys/testing_keys.dart';
import 'package:mgramseva/utils/date_formats.dart';
import 'package:mgramseva/utils/global_variables.dart';
import 'package:mgramseva/utils/Constants/I18KeyConstants.dart';
import 'package:mgramseva/utils/models.dart';
import 'package:mgramseva/widgets/tab_button.dart';

import 'Test Inputs/test_inputs.dart';

void main() {

  testWidgets("dashboard app test", (tester) async {
    var householdTestData = getTestData();
    app.main();
    await tester.pumpAndSettle(Duration(milliseconds: 2000));
    final dashboard = find.byType(GridTile).at(0);
    await tester.tap(dashboard);
    await tester.pumpAndSettle(Duration(milliseconds: 5000));

    final houseHoldSearch = find.byKey(Keys.household.HOUSEHOLD_SEARCH);
    final tab = find.byKey(Keys.dashboard.SECOND_TAB);
    final tab2 = find.byKey(Keys.dashboard.THIRD_TAB);
    final share = find.byKey(Keys.common.SHARE);

    /// household tab selection
    await tester.pumpAndSettle(Duration(milliseconds: 500));
    await tester.tap(tab);
    await tester.pumpAndSettle(Duration(milliseconds: 3000));

    await tester.tap(tab2);
    await tester.pumpAndSettle(Duration(milliseconds: 3000));

    final paginationArrow = find.byIcon(Icons.arrow_right);
    await tester.tap(paginationArrow);
    await tester.pumpAndSettle(Duration(milliseconds: 2000));

    final pagination = find.byKey(Keys.common.PAGINATION_DROPDOWN);
    await tester.pumpAndSettle(Duration(milliseconds: 1000));
    await tester.tap(pagination);
    await tester.pumpAndSettle(Duration(milliseconds: 3000));

    final pagingItem = find.byKey(Keys.common.PAGINATION_COUNT).first;
    await tester.pumpAndSettle(Duration(milliseconds: 1000));
    await tester.tap(pagingItem);
    await tester.pumpAndSettle(Duration(milliseconds: 5000));

    await tester.ensureVisible(houseHoldSearch);
    await tester.enterText(houseHoldSearch, householdTestData['householdSearch']);
    await tester.testTextInput.receiveAction(TextInputAction.done);
    await tester.pumpAndSettle(Duration(milliseconds: 5000));

    final downloadButton = find.byIcon(Icons.download_sharp).first;
    await tester.tap(downloadButton);
    await tester.pumpAndSettle(Duration(milliseconds: 2000));

    await tester.ensureVisible(share);
    await tester.tap(share);
    await tester.pumpAndSettle(Duration(milliseconds: 12000));

  });
}