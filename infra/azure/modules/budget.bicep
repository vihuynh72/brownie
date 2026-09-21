// A monthly ceiling with warnings before it, on this resource group alone.
//
// A budget does not stop anything: it sends mail, and the cost data behind it
// is hours late. It is here so that a mistake is noticed in days rather than
// at the end of the month, and it is deliberately separate from the model
// allowance, which is enforced by the application itself before each request
// and is billed by a different company entirely.

@description('Monthly ceiling in the billing currency for everything in this resource group.')
param monthlyAmount int

@description('Addresses that receive the warnings.')
param contactEmails array

@description('First day of the month the budget starts counting from. It defaults to the month of the first deployment; set it explicitly in the parameter file so that a deployment in a later month does not ask to move it, which the service can refuse.')
param startDate string = utcNow('yyyy-MM-01')

resource budget 'Microsoft.Consumption/budgets@2024-08-01' = {
  name: 'budget-monthly'
  properties: {
    category: 'Cost'
    amount: monthlyAmount
    timeGrain: 'Monthly'
    timePeriod: {
      startDate: startDate
    }
    notifications: {
      // Half is early enough to change something, four fifths is the last
      // comfortable moment, and the third fires on money already spent
      // rather than forecast, so a quiet month cannot hide a real overrun.
      halfway: {
        enabled: true
        operator: 'GreaterThan'
        threshold: 50
        contactEmails: contactEmails
        thresholdType: 'Actual'
      }
      nearly: {
        enabled: true
        operator: 'GreaterThan'
        threshold: 80
        contactEmails: contactEmails
        thresholdType: 'Actual'
      }
      reached: {
        enabled: true
        operator: 'GreaterThanOrEqualTo'
        threshold: 100
        contactEmails: contactEmails
        thresholdType: 'Actual'
      }
      forecastToExceed: {
        enabled: true
        operator: 'GreaterThan'
        threshold: 100
        contactEmails: contactEmails
        thresholdType: 'Forecasted'
      }
    }
  }
}

output budgetId string = budget.id
